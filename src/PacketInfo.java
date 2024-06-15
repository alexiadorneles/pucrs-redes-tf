public class PacketInfo {

    public PacketInfo() {
    }

    public PacketInfo(byte[] fileData, long CRC, int seq) {
        this.fileData = fileData;
        this.CRC = CRC;
        this.seq = seq;
    }

    public PacketInfo(byte[] fileData, long CRC, int seq, boolean finalPacket) {
        this.fileData = fileData;
        this.CRC = CRC;
        this.seq = seq;
        this.finalPacket = finalPacket;
    }

    private byte[] fileData;

    private long CRC;

    private int seq;

    private boolean finalPacket;

    public byte[] getFileData() {
        return fileData;
    }

    public void setFileData(byte[] fileData) {
        this.fileData = fileData;
    }

    public long getCRC() {
        return CRC;
    }

    public void setCRC(long CRC) {
        this.CRC = CRC;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public boolean isFinalPacket() {
        return finalPacket;
    }

    public void setFinalPacket(boolean finalPacket) {
        this.finalPacket = finalPacket;
    }
}