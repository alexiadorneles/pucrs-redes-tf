import java.util.zip.CRC32;

public class Utils {
    public static long calculateCRC(byte[] array) {
        CRC32 crc = new CRC32();
        crc.update(array);
        return crc.getValue();
    }
}
