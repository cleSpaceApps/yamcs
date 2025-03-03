package org.yamcs.client;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.yamcs.protobuf.ClientInfo;
import org.yamcs.protobuf.ClientInfo.ClientState;
import org.yamcs.protobuf.CreateProcessorRequest;
import org.yamcs.protobuf.EditClientRequest;
import org.yamcs.protobuf.EditProcessorRequest;
import org.yamcs.protobuf.ListProcessorsResponse;
import org.yamcs.protobuf.ProcessorInfo;
import org.yamcs.protobuf.ProcessorSubscriptionRequest;
import org.yamcs.protobuf.ServiceState;
import org.yamcs.protobuf.Statistics;
import org.yamcs.protobuf.WebSocketServerMessage.WebSocketExceptionData;
import org.yamcs.protobuf.WebSocketServerMessage.WebSocketSubscriptionData;
import org.yamcs.protobuf.Yamcs.ReplayRequest;
import org.yamcs.utils.TimeEncoding;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import io.netty.handler.codec.http.HttpMethod;

/**
 * controls processors in yamcs server via websocket
 *
 * @author nm
 *
 */
public class ProcessorControlClient implements ConnectionListener, WebSocketClientCallback, WebSocketResponseHandler {
    YamcsConnector yconnector;
    ProcessorListener yamcsMonitor;

    public ProcessorControlClient(YamcsConnector yconnector) {
        this.yconnector = yconnector;
        yconnector.addConnectionListener(this);
    }

    public void setProcessorListener(ProcessorListener yamcsMonitor) {
        this.yamcsMonitor = yamcsMonitor;
    }

    public void destroyProcessor(String name) throws ClientException {
        // TODO Auto-generated method stub

    }

    public CompletableFuture<byte[]> createProcessor(String instance, String name, String type,
            ReplayRequest spec, boolean persistent, int[] clients) throws ClientException {
        CreateProcessorRequest.Builder cprb = CreateProcessorRequest.newBuilder().setName(name).setType(type);
        cprb.setPersistent(persistent);
        for (int cid : clients) {
            cprb.addClientId(cid);
        }

        if (spec != null) {
            try {
                String json = JsonFormat.printer().print(spec);
                cprb.setConfig(json);
            } catch (IOException e) {
                throw new ClientException("Error encoding the request to json", e);
            }
        }

        RestClient restClient = yconnector.getRestClient();
        // POST "/api/processors/:instance"
        String resource = "/processors/" + instance;
        CompletableFuture<byte[]> cf = restClient.doRequest(resource, HttpMethod.POST, cprb.build().toByteArray());
        cf.whenComplete((result, exception) -> {
            if (exception != null) {
                yamcsMonitor.log("Exception creating processor: " + exception.getMessage());
            }
        });
        return cf;
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> connectToProcessor(String instance, String processorName, int[] clients) {
        RestClient restClient = yconnector.getRestClient();
        CompletableFuture<byte[]>[] cfs = new CompletableFuture[clients.length];

        for (int i = 0; i < clients.length; i++) {
            // PATCH /api/clients/:id
            String resource = "/clients/" + clients[i];
            EditClientRequest body = EditClientRequest.newBuilder().setInstance(instance).setProcessor(processorName)
                    .build();
            cfs[i] = restClient.doRequest(resource, HttpMethod.PATCH, body.toByteArray());
            cfs[i].whenComplete((result, exception) -> {
                if (exception != null) {
                    yamcsMonitor.log("Exception connecting client to processor: " + exception.getMessage());
                }
            });
        }

        return CompletableFuture.allOf(cfs);
    }

    public void pauseArchiveReplay(String instance, String name) {
        RestClient restClient = yconnector.getRestClient();
        // PATCH /api/processors/:instance/:name
        String resource = "/processors/" + instance + "/" + name;
        EditProcessorRequest body = EditProcessorRequest.newBuilder().setState("paused").build();
        CompletableFuture<byte[]> cf = restClient.doRequest(resource, HttpMethod.PATCH, body.toByteArray());
        cf.whenComplete((result, exception) -> {
            if (exception != null) {
                yamcsMonitor.log("Exception pauysing the processor: " + exception.getMessage());
            }
        });
    }

    public void resumeArchiveReplay(String instance, String name) {
        RestClient restClient = yconnector.getRestClient();
        // PATCH /api/processors/:instance/:name
        String resource = "/processors/" + instance + "/" + name;
        EditProcessorRequest body = EditProcessorRequest.newBuilder().setState("running").build();
        CompletableFuture<byte[]> cf = restClient.doRequest(resource, HttpMethod.PATCH, body.toByteArray());
        cf.whenComplete((result, exception) -> {
            if (exception != null) {
                yamcsMonitor.log("Exception resuming the processor: " + exception.getMessage());
            }
        });
    }

    public void seekArchiveReplay(String instance, String name, long newPosition) {
        RestClient restClient = yconnector.getRestClient();
        // PATCH /api/processors/:instance/:name
        String resource = "/processors/" + instance + "/" + name;
        EditProcessorRequest body = EditProcessorRequest.newBuilder().setSeek(TimeEncoding.toString(newPosition))
                .build();
        CompletableFuture<byte[]> cf = restClient.doRequest(resource, HttpMethod.PATCH, body.toByteArray());
        cf.whenComplete((result, exception) -> {
            if (exception != null) {
                yamcsMonitor.log("Exception seeking the processor: " + exception.getMessage());
            }
        });
    }

    @Override
    public void connecting(String url) {
    }

    private void receiveInitialConfig() {
        WebSocketRequest wsr = new WebSocketRequest("management", "subscribe");
        yconnector.performSubscription(wsr, this, this);

        yconnector.getRestClient().doRequest("/processors", HttpMethod.GET).whenComplete((response, exc) -> {
            if (exc == null) {
                try {
                    for (ProcessorInfo pi : ListProcessorsResponse.parseFrom(response).getProcessorList()) {
                        yamcsMonitor.processorUpdated(pi);
                    }
                } catch (InvalidProtocolBufferException e) {
                    throw new CompletionException(e);
                }
            }
        });

        ProcessorSubscriptionRequest.Builder optionsb = ProcessorSubscriptionRequest.newBuilder();
        optionsb.setAllInstances(true);
        optionsb.setAllProcessors(true);
        wsr = new WebSocketRequest("processor", "subscribe", optionsb.build());
        yconnector.performSubscription(wsr, this, this);
    }

    @Override
    public void connected(String url) {
        receiveInitialConfig();
    }

    @Override
    public void onMessage(WebSocketSubscriptionData data) {
        if (data.hasProcessorInfo()) {
            ProcessorInfo procInfo = data.getProcessorInfo();
            ServiceState servState = procInfo.getState();
            if (servState == ServiceState.TERMINATED || servState == ServiceState.FAILED) {
                yamcsMonitor.processorClosed(procInfo);
            } else {
                yamcsMonitor.processorUpdated(procInfo);
            }
        }
        if (data.hasClientInfo()) {
            ClientInfo cinfo = data.getClientInfo();
            ClientState cstate = cinfo.getState();
            if (cstate == ClientState.DISCONNECTED) {
                yamcsMonitor.clientDisconnected(cinfo);
            } else {
                yamcsMonitor.clientUpdated(cinfo);
            }
        }
        if (data.hasStatistics()) {
            Statistics s = data.getStatistics();
            yamcsMonitor.updateStatistics(s);
        }
    }

    @Override
    public void connectionFailed(String url, ClientException exception) {
    }

    @Override
    public void disconnected() {
    }

    @Override
    public void log(String message) {
    }

    @Override
    public void onException(WebSocketExceptionData e) {
        yamcsMonitor.log("Exception when performing subscription:" + e.getMessage());
    }
}
