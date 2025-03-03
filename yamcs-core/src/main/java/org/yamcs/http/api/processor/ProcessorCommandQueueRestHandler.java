package org.yamcs.http.api.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.yamcs.Processor;
import org.yamcs.commanding.CommandQueue;
import org.yamcs.commanding.CommandQueueManager;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.HttpException;
import org.yamcs.http.NotFoundException;
import org.yamcs.http.api.RestHandler;
import org.yamcs.http.api.RestRequest;
import org.yamcs.http.api.Route;
import org.yamcs.management.ManagementGpbHelper;
import org.yamcs.management.ManagementService;
import org.yamcs.protobuf.Commanding.CommandQueueEntry;
import org.yamcs.protobuf.Commanding.CommandQueueInfo;
import org.yamcs.protobuf.Commanding.QueueState;
import org.yamcs.protobuf.EditCommandQueueEntryRequest;
import org.yamcs.protobuf.EditCommandQueueRequest;
import org.yamcs.protobuf.ListCommandQueueEntries;
import org.yamcs.protobuf.ListCommandQueuesResponse;
import org.yamcs.security.SystemPrivilege;

public class ProcessorCommandQueueRestHandler extends RestHandler {

    @Route(path = "/api/processors/{instance}/{processor}/cqueues", method = "GET")
    public void listQueues(RestRequest req) throws HttpException {
        checkSystemPrivilege(req.getUser(), SystemPrivilege.ControlCommandQueue);

        Processor processor = verifyProcessor(req.getRouteParam("instance"), req.getRouteParam("processor"));
        CommandQueueManager mgr = verifyCommandQueueManager(processor);

        ListCommandQueuesResponse.Builder response = ListCommandQueuesResponse.newBuilder();
        List<CommandQueue> queues = new ArrayList<>(mgr.getQueues());
        Collections.sort(queues, (q1, q2) -> q1.getName().compareTo(q2.getName()));
        queues.forEach(q -> response.addQueue(toCommandQueueInfo(req, q, true)));
        completeOK(req, response.build());
    }

    @Route(path = "/api/processors/{instance}/{processor}/cqueues/{name}", method = "GET")
    public void getQueue(RestRequest req) throws HttpException {
        checkSystemPrivilege(req.getUser(), SystemPrivilege.ControlCommandQueue);

        Processor processor = verifyProcessor(req.getRouteParam("instance"), req.getRouteParam("processor"));
        CommandQueueManager mgr = verifyCommandQueueManager(processor);
        CommandQueue queue = verifyCommandQueue(req, mgr, req.getRouteParam("name"));

        CommandQueueInfo info = toCommandQueueInfo(req, queue, true);
        completeOK(req, info);
    }

    @Route(path = "/api/processors/{instance}/{processor}/cqueues/{name}", method = "PATCH")
    public void editQueue(RestRequest req) throws HttpException {
        checkSystemPrivilege(req.getUser(), SystemPrivilege.ControlCommandQueue);

        Processor processor = verifyProcessor(req.getRouteParam("instance"), req.getRouteParam("processor"));
        CommandQueueManager mgr = verifyCommandQueueManager(processor);
        CommandQueue queue = verifyCommandQueue(req, mgr, req.getRouteParam("name"));

        EditCommandQueueRequest body = req.bodyAsMessage(EditCommandQueueRequest.newBuilder()).build();
        String state = null;
        if (body.hasState()) {
            state = body.getState();
        }
        if (req.hasQueryParameter("state")) {
            state = req.getQueryParameter("state");
        }

        CommandQueue updatedQueue = queue;
        if (state != null) {
            switch (state.toLowerCase()) {
            case "disabled":
                updatedQueue = mgr.setQueueState(queue.getName(), QueueState.DISABLED);
                break;
            case "enabled":
                updatedQueue = mgr.setQueueState(queue.getName(), QueueState.ENABLED);
                break;
            case "blocked":
                updatedQueue = mgr.setQueueState(queue.getName(), QueueState.BLOCKED);
                break;
            default:
                throw new BadRequestException("Unsupported queue state '" + state + "'");
            }
        }
        CommandQueueInfo qinfo = toCommandQueueInfo(req, updatedQueue, true);
        completeOK(req, qinfo);
    }

    @Route(path = "/api/processors/{instance}/{processor}/cqueues/{name}/entries", method = "GET")
    public void listQueueEntries(RestRequest req) throws HttpException {
        checkSystemPrivilege(req.getUser(), SystemPrivilege.ControlCommandQueue);

        Processor processor = verifyProcessor(req.getRouteParam("instance"), req.getRouteParam("processor"));
        CommandQueueManager mgr = verifyCommandQueueManager(processor);
        CommandQueue queue = verifyCommandQueue(req, mgr, req.getRouteParam("name"));

        ListCommandQueueEntries.Builder responseb = ListCommandQueueEntries.newBuilder();
        for (PreparedCommand pc : queue.getCommands()) {
            CommandQueueEntry qEntry = ManagementGpbHelper.toCommandQueueEntry(queue, pc);
            responseb.addEntry(qEntry);
        }
        completeOK(req, responseb.build());
    }

    @Route(path = "/api/processors/{instance}/{processor}/cqueues/{cqueue}/entries/{uuid}", method = "PATCH")
    public void editQueueEntry(RestRequest req) throws HttpException {
        checkSystemPrivilege(req.getUser(), SystemPrivilege.ControlCommandQueue);

        Processor processor = verifyProcessor(req.getRouteParam("instance"), req.getRouteParam("processor"));
        CommandQueueManager mgr = verifyCommandQueueManager(processor);
        UUID entryId = UUID.fromString(req.getRouteParam("uuid"));

        EditCommandQueueEntryRequest body = req.bodyAsMessage(EditCommandQueueEntryRequest.newBuilder()).build();
        String state = null;
        if (body.hasState()) {
            state = body.getState();
        }
        if (req.hasQueryParameter("state")) {
            state = req.getQueryParameter("state");
        }

        if (state != null) {
            // TODO queue manager currently iterates over all queues, which doesn't really match
            // what we want. It would be better to assure only the queue from the URI is considered.
            switch (state.toLowerCase()) {
            case "released":
                mgr.sendCommand(entryId, false);
                break;
            case "rejected":
                String username = req.getUser().getName();
                mgr.rejectCommand(entryId, username);
                break;
            default:
                throw new BadRequestException("Unsupported state '" + state + "'");
            }
        }

        completeOK(req);
    }

    private CommandQueueInfo toCommandQueueInfo(RestRequest req, CommandQueue queue, boolean detail) {
        CommandQueueInfo.Builder b = CommandQueueInfo.newBuilder();
        b.setInstance(queue.getChannel().getInstance());
        b.setProcessorName(queue.getChannel().getName());
        b.setName(queue.getName());
        b.setState(queue.getState());
        b.setNbSentCommands(queue.getNbSentCommands());
        b.setNbRejectedCommands(queue.getNbRejectedCommands());
        if (queue.getStateExpirationRemainingS() != -1) {
            b.setStateExpirationTimeS(queue.getStateExpirationRemainingS());
        }
        if (detail) {
            for (PreparedCommand pc : queue.getCommands()) {
                CommandQueueEntry qEntry = ManagementGpbHelper.toCommandQueueEntry(queue, pc);
                b.addEntry(qEntry);
            }
        }
        return b.build();
    }

    private CommandQueueManager verifyCommandQueueManager(Processor processor) throws BadRequestException {
        ManagementService managementService = ManagementService.getInstance();
        CommandQueueManager mgr = managementService.getCommandQueueManager(processor);
        if (mgr == null) {
            throw new BadRequestException("Commanding not enabled for processor '" + processor.getName() + "'");
        }
        return mgr;
    }

    private CommandQueue verifyCommandQueue(RestRequest req, CommandQueueManager mgr, String queueName)
            throws NotFoundException {
        CommandQueue queue = mgr.getQueue(queueName);
        if (queue == null) {
            String processorName = mgr.getChannelName();
            String instance = mgr.getInstance();
            throw new NotFoundException(
                    "No queue named '" + queueName + "' (processor: '" + instance + "/" + processorName + "')");
        } else {
            return queue;
        }
    }
}
