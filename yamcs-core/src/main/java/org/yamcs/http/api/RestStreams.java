package org.yamcs.http.api;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.http.HttpException;
import org.yamcs.http.InternalServerErrorException;
import org.yamcs.utils.parser.ParseException;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.streamsql.StreamSqlException;

public class RestStreams {
    private static AtomicInteger streamCounter = new AtomicInteger();
    private static final Logger log = LoggerFactory.getLogger(RestStreams.class);

    public static void stream(String instance, String selectSql, StreamSubscriber s) throws HttpException {
        stream(instance, selectSql, Collections.emptyList(), s);
    }

    public static void stream(String instance, String selectSql, List<Object> args, StreamSubscriber s)
            throws HttpException {
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);

        String streamName = "rest_archive" + streamCounter.incrementAndGet();
        String sql = new StringBuilder("create stream ")
                .append(streamName)
                .append(" as ")
                .append(selectSql)
                .append(" nofollow")
                .toString();

        log.debug("Executing: {}", sql);
        try {
            ydb.execute(sql, args.toArray());
        } catch (StreamSqlException | ParseException e) {
            throw new InternalServerErrorException(e);
        }

        Stream stream = ydb.getStream(streamName);
        stream.addSubscriber(s);
        stream.start();
        return;
    }
}
