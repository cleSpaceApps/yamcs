package org.yamcs.http.api.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.YamcsException;
import org.yamcs.api.MediaType;
import org.yamcs.archive.IndexRequestListener;
import org.yamcs.archive.IndexServer;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.HttpException;
import org.yamcs.http.HttpRequestHandler;
import org.yamcs.http.InternalServerErrorException;
import org.yamcs.http.api.RestHandler;
import org.yamcs.http.api.RestRequest;
import org.yamcs.http.api.RestRequest.IntervalResult;
import org.yamcs.http.api.Route;
import org.yamcs.protobuf.BatchGetIndexRequest;
import org.yamcs.protobuf.Yamcs.ArchiveRecord;
import org.yamcs.protobuf.Yamcs.IndexRequest;
import org.yamcs.protobuf.Yamcs.IndexResult;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.utils.TimeEncoding;

import com.google.protobuf.util.JsonFormat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Serves archive indexes through a web api.
 *
 * <p>
 * These responses use chunked encoding with an unspecified content length, which enables us to send large dumps without
 * needing to determine a content length on the server.
 */
public class ArchiveIndexDownloadsRestHandler extends RestHandler {

    /**
     * indexes a combination of multiple indexes. If nothing is specified, sends all available
     */
    @Route(path = "/api/archive/{instance}/indexes", method = { "GET", "POST" })
    public void downloadIndexes(RestRequest req) throws HttpException {
        String instance = verifyInstance(req.getRouteParam("instance"));
        IndexServer indexServer = verifyIndexServer(instance);

        IndexRequest.Builder requestb = IndexRequest.newBuilder();
        requestb.setInstance(instance);
        IntervalResult ir = req.scanForInterval();
        if (ir.hasStart()) {
            requestb.setStart(ir.getStart());
        }
        if (ir.hasStop()) {
            requestb.setStop(ir.getStop());
        }

        if (req.hasQueryParameter("packetname")) {
            for (String names : req.getQueryParameterList("packetname")) {
                for (String name : names.split(",")) {
                    requestb.addTmPacket(NamedObjectId.newBuilder().setName(name.trim()));
                }
            }
        }

        Set<String> filter = new HashSet<>();
        if (req.hasQueryParameter("filter")) {
            for (String names : req.getQueryParameterList("filter")) {
                for (String name : names.split(",")) {
                    filter.add(name.toLowerCase().trim());
                }
            }
        }

        if (req.hasBody()) {
            BatchGetIndexRequest bgir = req.bodyAsMessage(BatchGetIndexRequest.newBuilder()).build();
            if (bgir.hasStart()) {
                requestb.setStart(TimeEncoding.parse(bgir.getStart()));
            }
            if (bgir.hasStop()) {
                requestb.setStop(TimeEncoding.parse(bgir.getStop()));
            }
            filter.addAll(bgir.getFilterList());
            for (String name : bgir.getPacketnameList()) {
                requestb.addTmPacket(NamedObjectId.newBuilder().setName(name));
            }
        }

        if (filter.isEmpty() && requestb.getTmPacketCount() == 0) {
            requestb.setSendAllTm(true);
            requestb.setSendAllPp(true);
            requestb.setSendAllCmd(true);
            requestb.setSendAllEvent(true);
            requestb.setSendCompletenessIndex(true);
        } else {
            requestb.setSendAllTm(filter.contains("tm") && requestb.getTmPacketCount() == 0);
            requestb.setSendAllPp(filter.contains("pp"));
            requestb.setSendAllCmd(filter.contains("commands"));
            requestb.setSendAllEvent(filter.contains("events"));
            requestb.setSendCompletenessIndex(filter.contains("completeness"));
        }

        try {
            indexServer.submitIndexRequest(requestb.build(), new ChunkedIndexResultProtobufEncoder(req, false));
        } catch (YamcsException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Route(path = "/api/archive/{instance}/indexes/packets", method = { "GET", "POST" })
    public void downloadPacketIndex(RestRequest req) throws HttpException {
        String instance = verifyInstance(req.getRouteParam("instance"));
        IndexServer indexServer = verifyIndexServer(instance);

        IndexRequest.Builder requestb = IndexRequest.newBuilder();
        requestb.setInstance(instance);
        IntervalResult ir = req.scanForInterval();
        if (ir.hasStart()) {
            requestb.setStart(ir.getStart());
        }
        if (ir.hasStop()) {
            requestb.setStop(ir.getStop());
        }

        if (req.hasQueryParameter("name")) {
            for (String names : req.getQueryParameterList("name")) {
                for (String name : names.split(",")) {
                    requestb.addTmPacket(NamedObjectId.newBuilder().setName(name.trim()));
                }
            }
        }
        if (requestb.getTmPacketCount() == 0) {
            requestb.setSendAllTm(true);
        }

        try {
            indexServer.submitIndexRequest(requestb.build(), new ChunkedIndexResultProtobufEncoder(req, true));
        } catch (YamcsException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Route(path = "/api/archive/{instance}/indexes/pp", method = { "GET", "POST" })
    public void downloadPpIndex(RestRequest req) throws HttpException {
        String instance = verifyInstance(req.getRouteParam("instance"));
        IndexServer indexServer = verifyIndexServer(instance);

        IndexRequest.Builder requestb = IndexRequest.newBuilder();
        requestb.setInstance(instance);
        IntervalResult ir = req.scanForInterval();
        if (ir.hasStart()) {
            requestb.setStart(ir.getStart());
        }
        if (ir.hasStop()) {
            requestb.setStop(ir.getStop());
        }
        requestb.setSendAllPp(true);

        try {
            indexServer.submitIndexRequest(requestb.build(), new ChunkedIndexResultProtobufEncoder(req, true));
        } catch (YamcsException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Route(path = "/api/archive/{instance}/indexes/commands", method = { "GET", "POST" })
    public void downloadCommandHistoryIndex(RestRequest req) throws HttpException {
        String instance = verifyInstance(req.getRouteParam("instance"));
        IndexServer indexServer = verifyIndexServer(instance);

        IndexRequest.Builder requestb = IndexRequest.newBuilder();
        requestb.setInstance(instance);
        IntervalResult ir = req.scanForInterval();
        if (ir.hasStart()) {
            requestb.setStart(ir.getStart());
        }
        if (ir.hasStop()) {
            requestb.setStop(ir.getStop());
        }
        requestb.setSendAllCmd(true);

        try {
            indexServer.submitIndexRequest(requestb.build(), new ChunkedIndexResultProtobufEncoder(req, true));
        } catch (YamcsException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Route(path = "/api/archive/{instance}/indexes/events", method = { "GET", "POST" })
    public void downloadEventIndex(RestRequest req) throws HttpException {
        String instance = verifyInstance(req.getRouteParam("instance"));
        IndexServer indexServer = verifyIndexServer(instance);

        IndexRequest.Builder requestb = IndexRequest.newBuilder();
        requestb.setInstance(instance);
        IntervalResult ir = req.scanForInterval();
        if (ir.hasStart()) {
            requestb.setStart(ir.getStart());
        }
        if (ir.hasStop()) {
            requestb.setStop(ir.getStop());
        }
        requestb.setSendAllEvent(true);

        try {
            indexServer.submitIndexRequest(requestb.build(), new ChunkedIndexResultProtobufEncoder(req, true));
        } catch (YamcsException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Route(path = "/api/archive/{instance}/indexes/completeness", method = { "GET", "POST" })
    public void downloadCompletenessIndex(RestRequest req) throws HttpException {
        String instance = verifyInstance(req.getRouteParam("instance"));
        IndexServer indexServer = verifyIndexServer(instance);

        IndexRequest.Builder requestb = IndexRequest.newBuilder();
        requestb.setInstance(instance);
        IntervalResult ir = req.scanForInterval();
        if (ir.hasStart()) {
            requestb.setStart(ir.getStart());
        }
        if (ir.hasStop()) {
            requestb.setStop(ir.getStop());
        }
        requestb.setSendCompletenessIndex(true);

        try {
            indexServer.submitIndexRequest(requestb.build(), new ChunkedIndexResultProtobufEncoder(req, true));
        } catch (YamcsException e) {
            throw new InternalServerErrorException(e);
        }
    }

    private static class ChunkedIndexResultProtobufEncoder implements IndexRequestListener {

        private static final Logger log = LoggerFactory.getLogger(ChunkedIndexResultProtobufEncoder.class);
        private static final int CHUNK_TRESHOLD = 8096;

        private final RestRequest req;
        private final MediaType contentType;
        private final boolean unpack;

        private ByteBuf buf;
        private ByteBufOutputStream bufOut;
        private ChannelFuture lastChannelFuture;

        private boolean first;
        private IndexResult.Builder indexResult;

        // If unpack, the result will be a stream of Archive Records, otherwise IndexResult
        public ChunkedIndexResultProtobufEncoder(RestRequest req, boolean unpack) {
            this.req = req;
            this.unpack = unpack;
            contentType = req.deriveTargetContentType();
            resetBuffer();
            first = true;
        }

        private void resetBuffer() {
            buf = req.getChannelHandlerContext().alloc().buffer();
            bufOut = new ByteBufOutputStream(buf);

        }

        @Override
        public void begin(IndexType type, String tblName) {
            if (!unpack) {
                if (indexResult != null) {
                    bufferIndexResult(indexResult.build());
                }
                indexResult = newBuilder(type.name(), tblName);
            }
        }

        @Override
        public void processData(ArchiveRecord ar) {
            if (first) {
                lastChannelFuture = HttpRequestHandler.startChunkedTransfer(req.getChannelHandlerContext(),
                        req.getHttpRequest(), contentType, null);
                req.reportStatusCode(200);
                first = false;
            }
            if (unpack) {
                bufferArchiveRecord(ar);
            } else {
                indexResult.addRecords(ar);
                if (indexResult.getRecordsCount() > 500) {
                    bufferIndexResult(indexResult.build());
                    indexResult = newBuilder(indexResult.getType(), indexResult.getTableName());
                }
            }

        }

        private void bufferArchiveRecord(ArchiveRecord msg) {
            try {
                if (MediaType.PROTOBUF.equals(contentType)) {
                    msg.writeDelimitedTo(bufOut);
                } else {
                    String json = JsonFormat.printer().print(msg);
                    bufOut.write(json.getBytes(StandardCharsets.UTF_8));
                }

                if (buf.readableBytes() >= CHUNK_TRESHOLD) {
                    bufOut.close();
                    writeChunk();
                    resetBuffer();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void bufferIndexResult(IndexResult msg) {
            try {
                if (MediaType.PROTOBUF.equals(contentType)) {
                    msg.writeDelimitedTo(bufOut);
                } else {
                    String json = JsonFormat.printer().print(msg);
                    bufOut.write(json.getBytes(StandardCharsets.UTF_8));
                }
                if (buf.readableBytes() >= CHUNK_TRESHOLD) {
                    bufOut.close();
                    writeChunk();
                    resetBuffer();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private IndexResult.Builder newBuilder(String type, String tblName) {
            IndexResult.Builder b = IndexResult.newBuilder().setType(type);
            if (tblName != null) {
                b.setTableName(tblName);
            }
            return b;
        }

        @Override
        public void finished(String token, boolean success) {
            if (first) { // empty result
                RestHandler.completeOK(req);
            } else {
                if (!unpack && (indexResult != null)) {
                    bufferIndexResult(indexResult.build());
                }
                try {
                    bufOut.close();
                    if (buf.readableBytes() > 0) {
                        writeChunk();
                    }
                    req.getChannelHandlerContext().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                            .addListener(ChannelFutureListener.CLOSE)
                            .addListener(l -> req.getCompletableFuture().complete(null));
                } catch (IOException e) {
                    log.error("Could not write final chunk of data", e);
                    req.getChannelHandlerContext().close();
                }
            }
        }

        private void writeChunk() throws IOException {
            int txSize = buf.readableBytes();
            req.addTransferredSize(txSize);
            lastChannelFuture = HttpRequestHandler.writeChunk(req.getChannelHandlerContext(), buf);
        }

    }

    private IndexServer verifyIndexServer(String instance) throws HttpException {
        verifyInstance(instance);
        List<IndexServer> services = yamcsServer.getServices(instance, IndexServer.class);
        if (services.isEmpty()) {
            throw new BadRequestException("Index service not enabled for instance '" + instance + "'");
        } else {
            return services.get(0);
        }
    }
}
