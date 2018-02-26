package org.prebid.server.adapter.pulsepoint;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import io.vertx.core.json.Json;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.adapter.OpenrtbAdapter;
import org.prebid.server.adapter.model.AdUnitBidWithParams;
import org.prebid.server.adapter.model.ExchangeCall;
import org.prebid.server.adapter.model.HttpRequest;
import org.prebid.server.adapter.pulsepoint.model.PulsepointParams;
import org.prebid.server.auction.model.AdUnitBid;
import org.prebid.server.auction.model.AdapterRequest;
import org.prebid.server.auction.model.PreBidRequestContext;
import org.prebid.server.bidder.BidderName;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.proto.request.PreBidRequest;
import org.prebid.server.proto.response.Bid;
import org.prebid.server.proto.response.MediaType;
import org.prebid.server.usersyncer.Usersyncer;
import org.prebid.server.util.HttpUtil;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pulsepoint {@link org.prebid.server.adapter.Adapter} implementation.
 * <p>
 * Maintainer email: <a href="mailto:info@prebid.org">info@prebid.org</a>
 */
public class PulsepointAdapter extends OpenrtbAdapter {

    private static final String NAME = BidderName.pulsepoint.name();

    private static final Set<MediaType> ALLOWED_MEDIA_TYPES =
            Collections.unmodifiableSet(EnumSet.of(MediaType.banner, MediaType.video));

    private final String endpointUrl;

    public PulsepointAdapter(Usersyncer usersyncer, String endpointUrl) {
        super(usersyncer);
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<HttpRequest> makeHttpRequests(AdapterRequest adapterRequest,
                                              PreBidRequestContext preBidRequestContext) {
        final BidRequest bidRequest = createBidRequest(adapterRequest, preBidRequestContext);
        final HttpRequest httpRequest = HttpRequest.of(endpointUrl, headers(), bidRequest);
        return Collections.singletonList(httpRequest);
    }

    private BidRequest createBidRequest(AdapterRequest adapterRequest, PreBidRequestContext preBidRequestContext) {
        final List<AdUnitBid> adUnitBids = adapterRequest.getAdUnitBids();

        validateAdUnitBidsMediaTypes(adUnitBids);

        final List<AdUnitBidWithParams<Params>> adUnitBidsWithParams = createAdUnitBidsWithParams(adUnitBids);
        final List<Imp> imps = makeImps(adUnitBidsWithParams, preBidRequestContext);
        validateImps(imps);

        final Publisher publisher = makePublisher(adUnitBidsWithParams);

        final PreBidRequest preBidRequest = preBidRequestContext.getPreBidRequest();
        return BidRequest.builder()
                .id(preBidRequest.getTid())
                .at(1)
                .tmax(preBidRequest.getTimeoutMillis())
                .imp(imps)
                .app(makeApp(preBidRequestContext, publisher))
                .site(makeSite(preBidRequestContext, publisher))
                .device(deviceBuilder(preBidRequestContext).build())
                .user(makeUser(preBidRequestContext))
                .source(makeSource(preBidRequestContext))
                .build();
    }

    private static List<AdUnitBidWithParams<Params>> createAdUnitBidsWithParams(List<AdUnitBid> adUnitBids) {
        return adUnitBids.stream()
                .map(adUnitBid -> AdUnitBidWithParams.of(adUnitBid, parseAndValidateParams(adUnitBid)))
                .collect(Collectors.toList());
    }

    private static Params parseAndValidateParams(AdUnitBid adUnitBid) {
        final ObjectNode paramsNode = adUnitBid.getParams();
        if (paramsNode == null) {
            throw new PreBidException("Pulsepoint params section is missing");
        }

        final PulsepointParams params;
        try {
            params = Json.mapper.convertValue(paramsNode, PulsepointParams.class);
        } catch (IllegalArgumentException e) {
            // a weird way to pass parsing exception
            throw new PreBidException(e.getMessage(), e.getCause());
        }

        final Integer publisherId = params.getPublisherId();
        if (publisherId == null || publisherId == 0) {
            throw new PreBidException("Missing PublisherId param cp");
        }
        final Integer tagId = params.getTagId();
        if (tagId == null || tagId == 0) {
            throw new PreBidException("Missing TagId param ct");
        }
        final String adSize = params.getAdSize();
        if (StringUtils.isEmpty(adSize)) {
            throw new PreBidException("Missing AdSize param cf");
        }

        final String[] sizes = adSize.toLowerCase().split("x");
        if (sizes.length != 2) {
            throw new PreBidException(String.format("Invalid AdSize param %s", adSize));
        }
        final int width;
        try {
            width = Integer.parseInt(sizes[0]);
        } catch (NumberFormatException e) {
            throw new PreBidException(String.format("Invalid Width param %s", sizes[0]));
        }

        final int height;
        try {
            height = Integer.parseInt(sizes[1]);
        } catch (NumberFormatException e) {
            throw new PreBidException(String.format("Invalid Height param %s", sizes[1]));
        }

        return Params.of(String.valueOf(publisherId), String.valueOf(tagId), width, height);
    }

    private static List<Imp> makeImps(List<AdUnitBidWithParams<Params>> adUnitBidsWithParams,
                                      PreBidRequestContext preBidRequestContext) {
        return adUnitBidsWithParams.stream()
                .flatMap(adUnitBidWithParams -> makeImpsForAdUnitBid(adUnitBidWithParams, preBidRequestContext))
                .collect(Collectors.toList());
    }

    private static Stream<Imp> makeImpsForAdUnitBid(AdUnitBidWithParams<Params> adUnitBidWithParams,
                                                    PreBidRequestContext preBidRequestContext) {
        final AdUnitBid adUnitBid = adUnitBidWithParams.getAdUnitBid();
        final Params params = adUnitBidWithParams.getParams();

        return allowedMediaTypes(adUnitBid, ALLOWED_MEDIA_TYPES).stream()
                .map(mediaType -> impBuilderWithMedia(mediaType, adUnitBid, params)
                        .id(adUnitBid.getAdUnitCode())
                        .instl(adUnitBid.getInstl())
                        .secure(preBidRequestContext.getSecure())
                        .tagid(params.getTagId())
                        .build());
    }

    private static Imp.ImpBuilder impBuilderWithMedia(MediaType mediaType, AdUnitBid adUnitBid, Params params) {
        final Imp.ImpBuilder impBuilder = Imp.builder();

        switch (mediaType) {
            case video:
                impBuilder.video(videoBuilder(adUnitBid).build());
                break;
            case banner:
                impBuilder.banner(makeBanner(adUnitBid, params.getAdSizeWidth(), params.getAdSizeHeight()));
                break;
            default:
                // unknown media type, just skip it
        }
        return impBuilder;
    }

    private static Banner makeBanner(AdUnitBid adUnitBid, Integer width, Integer height) {
        return bannerBuilder(adUnitBid)
                .w(width)
                .h(height)
                .build();
    }

    private Publisher makePublisher(List<AdUnitBidWithParams<Params>> adUnitBidsWithParams) {
        final String publisherId = adUnitBidsWithParams.stream()
                .map(adUnitBidWithParams -> adUnitBidWithParams.getParams().getPublisherId())
                .reduce((first, second) -> second).orElse(null);
        return Publisher.builder().id(publisherId).build();
    }

    private static App makeApp(PreBidRequestContext preBidRequestContext, Publisher publisher) {
        final App app = preBidRequestContext.getPreBidRequest().getApp();
        return app == null ? null : app.toBuilder()
                .publisher(publisher)
                .build();
    }

    private static Site makeSite(PreBidRequestContext preBidRequestContext, Publisher publisher) {
        final Site.SiteBuilder siteBuilder = siteBuilder(preBidRequestContext);
        return siteBuilder == null ? null : siteBuilder
                .publisher(publisher)
                .build();
    }

    @Override
    public List<Bid.BidBuilder> extractBids(AdapterRequest adapterRequest, ExchangeCall exchangeCall) {
        return responseBidStream(exchangeCall.getBidResponse())
                .map(bid -> toBidBuilder(bid, adapterRequest))
                .collect(Collectors.toList());
    }

    private static Bid.BidBuilder toBidBuilder(com.iab.openrtb.response.Bid bid, AdapterRequest adapterRequest) {
        final AdUnitBid adUnitBid = lookupBid(adapterRequest.getAdUnitBids(), bid.getImpid());
        return Bid.builder()
                .bidder(adUnitBid.getBidderCode())
                .bidId(adUnitBid.getBidId())
                .code(bid.getImpid())
                .price(bid.getPrice())
                .adm(bid.getAdm())
                .creativeId(bid.getCrid())
                .width(bid.getW())
                .height(bid.getH());
    }

    @AllArgsConstructor(staticName = "of")
    @Value
    private static class Params {

        String publisherId;

        String tagId;

        Integer adSizeWidth;

        Integer adSizeHeight;
    }
}
