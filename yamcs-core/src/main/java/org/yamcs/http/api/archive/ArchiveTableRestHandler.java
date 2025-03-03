package org.yamcs.http.api.archive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.YamcsServer;
import org.yamcs.api.MediaType;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.ForbiddenException;
import org.yamcs.http.HttpContentToByteBufDecoder;
import org.yamcs.http.HttpException;
import org.yamcs.http.HttpRequestHandler;
import org.yamcs.http.InternalServerErrorException;
import org.yamcs.http.NotFoundException;
import org.yamcs.http.api.RestHandler;
import org.yamcs.http.api.Route;
import org.yamcs.http.api.Router.RouteMatch;
import org.yamcs.protobuf.RestExceptionMessage;
import org.yamcs.protobuf.Table;
import org.yamcs.protobuf.Table.Row;
import org.yamcs.protobuf.Table.Row.Cell;
import org.yamcs.protobuf.Table.Row.ColumnInfo;
import org.yamcs.protobuf.Table.TableLoadResponse;
import org.yamcs.security.SystemPrivilege;
import org.yamcs.security.User;
import org.yamcs.yarch.ColumnDefinition;
import org.yamcs.yarch.ColumnSerializer;
import org.yamcs.yarch.ColumnSerializerFactory;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.DataType._type;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.TableDefinition;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;

public class ArchiveTableRestHandler extends RestHandler {

    AtomicInteger count = new AtomicInteger();

    @Route(path = "/api/archive/{instance}/tables/{name}/data", method = "POST", dataLoad = true)
    public void loadTableData(ChannelHandlerContext ctx, HttpRequest req, RouteMatch match) throws HttpException {
        User user = ctx.channel().attr(HttpRequestHandler.CTX_USER).get();
        if (!user.hasSystemPrivilege(SystemPrivilege.WriteTables)) {
            throw new ForbiddenException("Insufficient privileges");
        }
        MediaType contentType = HttpRequestHandler.getContentType(req);
        if (contentType != MediaType.PROTOBUF) {
            throw new BadRequestException(
                    "Invalid Content-Type " + contentType + " for table load; please use " + MediaType.PROTOBUF);
        }

        String instance = match.getRouteParam("instance");
        if (!YamcsServer.hasInstance(instance)) {
            throw new NotFoundException("No instance named '" + instance + "'");
        }
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);

        String tableName = match.getRouteParam("name");

        TableDefinition table = ydb.getTable(tableName);
        if (table == null) {
            throw new NotFoundException("No table named '" + tableName + "' (instance: '" + ydb.getName() + "')");
        }
        Stream inputStream;
        try {
            String sname = "rest_load_table" + count.incrementAndGet();
            String stmt = "create stream " + sname + table.getTupleDefinition().getStringDefinition();
            ydb.execute(stmt);
            ydb.execute("insert into " + tableName + " select * from " + sname);
            inputStream = ydb.getStream(sname);
        } catch (Exception e) {
            throw new InternalServerErrorException(e);
        }

        ChannelPipeline pipeline = ctx.pipeline();

        pipeline.addLast("bytebufextractor", new HttpContentToByteBufDecoder());
        pipeline.addLast("frameDecoder", new ProtobufVarint32FrameDecoder());
        pipeline.addLast("protobufDecoder", new ProtobufDecoder(Row.getDefaultInstance()));
        pipeline.addLast("loader", new TableLoader(table, req, inputStream));
    }

    static class TableLoader extends SimpleChannelInboundHandler<Row> {
        private static final Logger log = LoggerFactory.getLogger(TableLoader.class);
        int count = 0;
        private HttpRequest req;
        boolean errorState = false;
        Stream inputStream;
        TableDefinition tblDef;
        Map<Integer, ColumnSerializer<?>> serializers = new HashMap<>();
        Map<Integer, ColumnDefinition> colDefinitions = new HashMap<>();
        static final int MAX_COLUMNS = 65535;

        public TableLoader(TableDefinition tblDef, HttpRequest req, Stream inputStream) {
            this.req = req;
            this.inputStream = inputStream;
            this.tblDef = tblDef;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Row msg) throws Exception {
            if (errorState) {
                return;
            }

            try {
                Tuple t = rowToTuple(msg);
                inputStream.emitTuple(t);
            } catch (IllegalArgumentException e) {
                errorState = true;
                sendErrorAndCloseAfter2Seconds(ctx, HttpResponseStatus.BAD_REQUEST, e.toString());
                inputStream.close();
                return;
            }
            count++;
        }

        private Tuple rowToTuple(Row row) throws IOException {
            for (ColumnInfo cinfo : row.getColumnList()) {
                if (!cinfo.hasId() || !cinfo.hasName() || !cinfo.hasType()) {
                    throw new IllegalArgumentException(
                            "Invalid row provided, no id or name  or type in the column info");
                }
                int colId = cinfo.getId();
                String cname = cinfo.getName();
                String ctype = cinfo.getType();
                DataType type = DataType.byName(ctype);
                ColumnDefinition cd = new ColumnDefinition(cname, type);
                ColumnSerializer<?> cs;
                if (type.val == _type.PROTOBUF) {
                    cs = ColumnSerializerFactory.getProtobufSerializer(cd);
                } else if (type.val == _type.ENUM) {
                    cs = ColumnSerializerFactory.getBasicColumnSerializer(DataType.STRING);
                } else {
                    cs = ColumnSerializerFactory.getBasicColumnSerializer(type);
                }
                serializers.put(colId, cs);
                colDefinitions.put(colId, cd);
                if (serializers.size() > MAX_COLUMNS) {
                    throw new IllegalArgumentException("Too many columns specified");
                }
            }
            TupleDefinition tdef = new TupleDefinition();
            List<Object> values = new ArrayList<>(row.getCellCount());
            for (Cell cell : row.getCellList()) {
                if (!cell.hasColumnId() || !cell.hasData()) {
                    throw new IllegalArgumentException("Invalid cell provided, no id or no data");
                }
                int colId = cell.getColumnId();
                ColumnDefinition cd = colDefinitions.get(colId);
                if (cd == null) {
                    throw new IllegalArgumentException("Invalid column id " + colId
                            + " specified. It has to be defined  by a the ColumnInfo message");
                }
                tdef.addColumn(cd);
                ColumnSerializer<?> cs = serializers.get(colId);
                Object v = cs.fromByteArray(cell.getData().toByteArray(), cd);
                values.add(v);
            }
            return new Tuple(tdef, values);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (errorState) {
                return;
            }
            errorState = true;
            log.warn("Exception caught in the table load pipeline, closing the connection: {}", cause.getMessage());
            inputStream.close();
            if (cause instanceof DecoderException) {
                Throwable t = cause.getCause();
                sendErrorAndCloseAfter2Seconds(ctx, HttpResponseStatus.BAD_REQUEST, t.toString());
            } else {
                sendErrorAndCloseAfter2Seconds(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, cause.toString());
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object obj) throws Exception {
            if (obj == HttpRequestHandler.CONTENT_FINISHED_EVENT) {
                log.debug("{} table load finished; inserted {} records ", ctx.channel(), count);
                inputStream.close();
                TableLoadResponse tlr = TableLoadResponse.newBuilder().setRowsLoaded(count).build();
                HttpRequestHandler.sendMessageResponse(ctx, req, HttpResponseStatus.OK, tlr);
            }
        }

        void sendErrorAndCloseAfter2Seconds(ChannelHandlerContext ctx, HttpResponseStatus status, String msg) {
            RestExceptionMessage.Builder exb = RestExceptionMessage.newBuilder().setType("TableLoadError").setMsg(msg);
            exb.setExtension(Table.rowsLoaded, count);
            HttpRequestHandler.sendMessageResponse(ctx, req, status, exb.build(), false).addListener(f -> {
                // schedule close after 2 seconds so the client has the chance to read the error message
                // see https://groups.google.com/forum/#!topic/netty/eVB6SMcXOHI
                ctx.executor().schedule(() -> {
                    ctx.close();
                }, 2, TimeUnit.SECONDS);
            });
        }
    }
}
