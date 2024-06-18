import java.io.File;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

public class Sender {

    static List<PacketInfo> packets = new ArrayList<>();

    static final int SLOW_START_MAX_DATA_PACKAGES = 2;
    static final int PACKET_SIZE = 10; // Tamanho dos pacotes em bytes
    static final int DEBUG_TIMEOUT = 1000;

    private static String ipAddress;
    private static InetAddress inetAddress;
    private static int port;
    private static String fileName;

    static DatagramSocket datagramSocket;

    public static void main(String[] args) throws Exception {
        ipAddress = "127.0.0.1"; // Endereço IP do destinatário
        port = 9876; // Porta utilizada na comunicaçao
        fileName = "file.txt"; // Nome do arquivo a ser transferido

        startConnection();
    }

    public static void startConnection() throws Exception {
        inetAddress = InetAddress.getByName(ipAddress);

        datagramSocket = new DatagramSocket();
        System.out.println("Sender connection started...");

        // Define o timeout para o socket (3 segundos neste caso)
        datagramSocket.setSoTimeout(3 * 1000);

        System.out.println("\nConnection established!");

        createPackets();

        // Neste ponto, todos os pacotes estão criados e prontos para envio ao servidor
        int listIterator = initializeSlowStart(SLOW_START_MAX_DATA_PACKAGES);

        if (listIterator >= packets.size()) {
            System.out.println("All sent, no need for avoidance...");
        } else {
            congestionAvoidance(listIterator);
            System.out.println("\nConnection closed!");
        }
    }

    public static int initializeSlowStart(int packageLimit) throws Exception {
        System.out.println();
        int packetsToSend = 1;

        int listIterator = 0;

        int actualPackageLimit = 1;
        int packetCalculation = 1;

        // Calcula o número máximo de pacotes que podem ser enviados
        while (packetCalculation != packageLimit) {
            packetCalculation *= 2;
            actualPackageLimit = actualPackageLimit * 2 + 1;
        }

        List<String> acksReceived = new ArrayList<String>();

        PacketInfo info;

        // Envia os pacotes
        try {
            while (packetsToSend <= actualPackageLimit) {
                for (listIterator = listIterator; listIterator < packetsToSend; listIterator++) {
                    try {
                        info = packets.get(listIterator);
                    } catch (Exception ex) {
                        // Terminou a iteração, todos os pacotes foram enviados
                        break;
                    }

                    sendPacket(info);

                    PacketResponse response = receivePacket();

                    acksReceived.add("Received response: " + response.getMessage() + ":" + response.getSeq());

                }

                for (int i = 0; i < acksReceived.size(); i++) {
                    System.out.println(acksReceived.get(i));
                }

                acksReceived = new ArrayList<String>();

                packetsToSend = packetsToSend * 2 + 1;
            }
        } catch (SocketTimeoutException ex) {
            for (int i = 0; i < acksReceived.size(); i++) {
                System.out.println(acksReceived.get(i));
            }

            acksReceived = new ArrayList<String>();

            System.out.println("Timeout");
            System.out.println("Resending packet...");

            Thread.sleep(DEBUG_TIMEOUT);
            initializeSlowStart(SLOW_START_MAX_DATA_PACKAGES);
        }
        return listIterator;
    }

    // Faz a criação dos pacotes e os adiciona à lista de pacotes
    public static void congestionAvoidance(int listIterator) throws Exception {
        System.out.println("Reached congestionAvoidance!");

        PacketInfo packetInfo = null;

        PacketResponse response = null;

        List<String> acksReceived = new ArrayList<String>();

        int quantPacketSend = SLOW_START_MAX_DATA_PACKAGES + 1;

        try {
            while (packets.size() != listIterator) {

                for (int i = 0; i < quantPacketSend; i++) {

                    try {
                        packetInfo = packets.get(listIterator);
                    } catch (Exception ex) {
                        // Terminou a iteração, todos os pacotes foram enviados
                        break;
                    }

                    sendPacket(packetInfo);
                    response = receivePacket();
                    acksReceived.add("Received response: " + response.getMessage() + ":" + response.getSeq());

                    listIterator++;
                }

                for (int i = 0; i < acksReceived.size(); i++) {
                    System.out.println(acksReceived.get(i));
                }

                acksReceived = new ArrayList<String>();

                quantPacketSend++;
            }

        } catch (SocketTimeoutException ex) {

            for (int i = 0; i < acksReceived.size(); i++) {
                System.out.println(acksReceived.get(i));
            }

            acksReceived = new ArrayList<String>();

            System.out.println("Timeout");
            System.out.println("Resending packet...");

            initializeSlowStart(SLOW_START_MAX_DATA_PACKAGES);

        }
    }

    public static PacketResponse parseResponseMessage(DatagramPacket message) {
        String[] split = new String(message.getData()).split(Config.MESSAGE_SPLITTER);

        if (split[0].trim().equals("FINISHED")) {
            // Não importa a sequência aqui, pois é o último pacote do servidor
            return new PacketResponse(split[0], 1);
        }

        return new PacketResponse(split[0], Integer.parseInt(split[1].trim()));
    }

    public static void sendPacket(PacketInfo packet) throws Exception {
        byte[] fileData = insertRandomError(packet.getFileData(), 0.15, packet.getSeq());
        String finalFlag = packet.isFinalPacket() ? Config.MESSAGE_SPLITTER
                + packet.isFinalPacket() : "";
        String message = Arrays.toString(fileData) + Config.MESSAGE_SPLITTER + packet.getCRC() + Config.MESSAGE_SPLITTER
                + packet.getSeq()
                + finalFlag;
        System.out.println("Sending message: " + message);
        byte[] packetData = message.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(packetData, packetData.length, inetAddress, port);
        datagramSocket.send(sendPacket);
        System.out.println("Packet " + packet.getSeq() + " sent");
        Thread.sleep(DEBUG_TIMEOUT);
    }

    public static PacketResponse receivePacket() throws Exception {
        byte[] responseData = new byte[1024];

        DatagramPacket receivePacket = new DatagramPacket(responseData, responseData.length, inetAddress, port);

        datagramSocket.receive(receivePacket);

        PacketResponse response = parseResponseMessage(receivePacket);

        return response;
    }

    public static long calculateCRC(byte[] array) {
        CRC32 crc = new CRC32();

        crc.update(array);

        long value = crc.getValue();

        return value;
    }

    public static void createPackets() throws Exception {
        // Se estiver usando execução MOCK, defina este valor como o próximo número de sequência a ser enviado.
        int sequenceNumber = 1;

        File file = new File(fileName);
        FileInputStream fileInputStream = new FileInputStream(file);

        // Lê o arquivo em bytes
        byte[] fileBytes = new byte[(int) file.length()];
        fileInputStream.read(fileBytes);
        fileInputStream.close();

        // Divide o arquivo em pacotes de 10 bytes
        int offset = 0;
        long fileSize = file.length();
        int bytesRead = 0;

        while (offset < fileBytes.length) {
            byte[] packetData = new byte[PACKET_SIZE];

            // Preenche o pacote com dados do arquivo
            for (int i = 0; i < PACKET_SIZE; i++) {
                bytesRead++;
                if (offset < fileBytes.length) {
                    packetData[i] = fileBytes[offset++];
                } else {
                    // Preenchimento no último pacote, se necessário
                    packetData[i] = 0; // por exemplo, preenche com zero
                }
            }

            // Calcula o CRC
            long crc = calculateCRC(packetData);
            PacketInfo packet = new PacketInfo(packetData, crc, sequenceNumber);
            packet.setFinalPacket(bytesRead >= fileSize);
            packets.add(packet);
            sequenceNumber++;
        }

    }

    public static byte[] insertRandomError(byte[] packetData, double errorProbability, int seq) {
        if (seq == 1)
            return packetData;
        // Gera um número aleatório entre 0 e 1
        double randomValue = Math.random();
        Random random = new Random();

        // Verifica se ocorre um erro com base na probabilidade
        if (randomValue < errorProbability) {
            // Gera um byte aleatório entre 0 e 255
            int randomIntInByte = random.nextInt(127); // Gera um número entre 0 e 255

            // Converte o número para um byte, garantindo que seja positivo
            byte randomByte = (byte) (randomIntInByte & 0xFF);

            // Simula um erro: por exemplo, modifica o primeiro byte
            packetData[0] = randomByte;

            System.out.println("Error inserted in packet with seq: " + seq);
            System.out.println("Modified packet: " + Arrays.toString(packetData));
            System.out.println();
        }

        return packetData;
    }

}