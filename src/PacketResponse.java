public class PacketResponse {

    public PacketResponse() {
    }

    public PacketResponse(String message, int seq) {
        this.message = message;
        this.seq = seq;
    }

    private String message;

    private int seq;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }
}