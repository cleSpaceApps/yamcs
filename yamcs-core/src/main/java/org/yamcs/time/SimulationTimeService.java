package org.yamcs.time;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.YamcsServer;
import org.yamcs.http.HttpException;
import org.yamcs.http.HttpServer;
import org.yamcs.http.NotFoundException;
import org.yamcs.http.api.RestHandler;
import org.yamcs.http.api.RestRequest;
import org.yamcs.http.api.Route;
import org.yamcs.protobuf.SetSimulationTimeRequest;
import org.yamcs.utils.TimeEncoding;

/**
 * Simulation time model where the simulation starts at javaTime0
 *
 * the simElapsedTime is the simulation elapsedTime counting from javaTime0
 *
 * the speed is the simulation speed. If greater than 0, the time passes even without the update of the simElapsedTime
 *
 *
 * @author nm
 *
 */
public class SimulationTimeService implements TimeService {
    double speed;
    long javaTime0;
    long javaTime; // this is the java time when the last simElapsedTime has been set
    long simElapsedTime;
    private static final Logger log = LoggerFactory.getLogger(SimulationTimeService.class);

    public SimulationTimeService(String yamcsInstance) {
        javaTime0 = System.currentTimeMillis();
        javaTime = javaTime0;
        simElapsedTime = 0;
        speed = 1;

        HttpServer httpServer = YamcsServer.getServer().getGlobalServices(HttpServer.class).get(0);
        httpServer.addApiHandler(yamcsInstance, new SimTimeRestHandler());
    }

    @Override
    public long getMissionTime() {
        long t;
        t = (long) (javaTime0 + simElapsedTime + speed * (System.currentTimeMillis() - javaTime));
        return t;
    }

    public void setSimElapsedTime(long simElapsedTime) {
        javaTime = System.currentTimeMillis();
        this.simElapsedTime = simElapsedTime;
    }

    public void setTime0(long time0) {
        javaTime0 = time0;
    }

    public void setSimSpeed(double simSpeed) {
        this.speed = simSpeed;
    }

    /**
     * Handles incoming requests related to SimTime
     */
    public static class SimTimeRestHandler extends RestHandler {

        @Route(path = "/api/time/{instance}", method = { "PUT", "POST" })
        public void setSimTime(RestRequest req) throws HttpException {
            String instance = verifyInstance(req.getRouteParam("instance"));
            TimeService ts = yamcsServer.getInstance(instance).getTimeService();
            if (!(ts instanceof SimulationTimeService)) {
                log.warn("Simulation time service requested for a non-simulation TimeService {}", ts);
                throw new NotFoundException();
            }

            SimulationTimeService sts = (SimulationTimeService) ts;
            SetSimulationTimeRequest request = req.bodyAsMessage(SetSimulationTimeRequest.newBuilder()).build();

            if (request.hasTime0()) {
                sts.setTime0(TimeEncoding.fromProtobufTimestamp(request.getTime0()));
            } else if (request.hasTime0UTC()) {
                sts.setTime0(TimeEncoding.parse(request.getTime0UTC()));
            } else if (request.hasYamcsTime0()) {
                sts.setTime0(request.getYamcsTime0());
            }

            if (request.hasSimSpeed()) {
                sts.setSimSpeed(request.getSimSpeed());
            }

            if (request.hasSimElapsedTime()) {
                sts.setSimElapsedTime(request.getSimElapsedTime());
            }

            completeOK(req);
        }
    }
}
