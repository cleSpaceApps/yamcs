package org.yamcs.cfdp;

import java.util.ArrayList;
import java.util.List;

import org.yamcs.cfdp.pdu.ActionCode;
import org.yamcs.cfdp.pdu.CfdpHeader;
import org.yamcs.cfdp.pdu.CfdpPacket;
import org.yamcs.cfdp.pdu.FaultHandlerOverride;
import org.yamcs.cfdp.pdu.FileStoreRequest;
import org.yamcs.cfdp.pdu.LV;
import org.yamcs.cfdp.pdu.MessageToUser;
import org.yamcs.cfdp.pdu.MetadataPacket;
import org.yamcs.cfdp.pdu.TLV;
import org.yamcs.protobuf.Cfdp.TransferDirection;
import org.yamcs.protobuf.Cfdp.TransferState;
import org.yamcs.yarch.Bucket;
import org.yamcs.yarch.Stream;

public class CfdpTransfer extends CfdpTransaction {

    private enum CfdpTransferState {
        START,
        METADATA_SENT
    }

    private long startTime;

    private CfdpTransferState currentState;
    private TransferState state;
    private long transferred;

    private Bucket bucket;
    private String object;
    private String remotePath;
    private TransferDirection transferDirection;

    private long totalSize;

    private PutRequest request;

    public CfdpTransfer(PutRequest request, Stream cfdpOut) {
        super(cfdpOut);
        this.request = request;
        this.currentState = CfdpTransferState.START;
    }

    public TransferState getState() {
        return this.state;
    }

    public long getTransferredSize() {
        return this.transferred;
    }

    public boolean isOngoing() {
        return state == TransferState.RUNNING || state == TransferState.PAUSED;
    }

    @Override
    public void step() {
        switch (currentState) {
        case START:
            this.startTime = System.currentTimeMillis();
            // create packet header
            CfdpHeader header = new CfdpHeader(
                    true, // it's a file directive
                    false, // it's sent towards the receiver
                    false, // not acknowledged // TODO, is this okay?
                    false, // no CRC
                    4, // TODO, hardcoded entity length
                    4, // TODO, hardcoded sequence number length
                    getId(), // my Entity Id
                    request.getDestinationId(), // the id of the target
                    getNextSequenceNumber());

            // TODO, only supports the creation of new files at the moment
            List<FileStoreRequest> fsrs = new ArrayList<FileStoreRequest>();
            fsrs.add(new FileStoreRequest(ActionCode.CreateFile, new LV(request.getTargetPath())));

            CfdpPacket metadata = new MetadataPacket(
                    false, // TODO no segmentation
                    request.getPacketLength(),
                    "", // no source file name, the data will come from a bucket
                    request.getTargetPath(),
                    fsrs,
                    new ArrayList<MessageToUser>(), // no user messages
                    new ArrayList<FaultHandlerOverride>(), // no fault handler overides
                    new TLV((byte) 0x05, new byte[0]), // empty flow label
                    header);
            sendPacket(metadata);
            this.currentState = CfdpTransferState.METADATA_SENT;
            break;
        default:
            // TODO
            break;
        }
    }

    public CfdpTransferState getCfdpState() {
        return this.currentState;
    }

    public Bucket getBucket() {
        return this.bucket;
    }

    public String getObjectName() {
        return this.object;
    }

    public String getRemotePath() {
        return this.remotePath;
    }

    public TransferDirection getDirection() {
        return this.transferDirection;
    }

    public long getTotalSize() {
        return this.totalSize;
    }

    public CfdpTransfer cancel() {
        // IF cancelled, return myself, otherwise return null id, otherwise return null
        return this;
    }

    public CfdpTransfer pause() {
        // IF cancelled, return myself, otherwise return null id, otherwise return null
        return this;
    }

    public CfdpTransfer resume() {
        // IF cancelled, return myself, otherwise return null id, otherwise return null
        return this;
    }

}
