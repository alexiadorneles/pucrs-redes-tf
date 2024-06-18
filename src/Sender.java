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

public class Sender {

    static List<PacketInfo> packets = new ArrayList<>();

    private static InetAddress inetAddress;
    private static int port;
    private static String fileName;

    static DatagramSocket datagramSocket;

    public static void main(String[] args) throws Exception {
        port = 9876; // Porta utilizada na comunicaçao
        fileName = "file.txt"; // Nome do arquivo a ser transferido
        startConnection();
    }

    public static void startConnection() throws Exception {
        inetAddress = InetAddress.getByName(Config.HOST);
        datagramSocket = new DatagramSocket();
        System.out.println("Sender connection started...");
        datagramSocket.setSoTimeout(3 * 1000);
        System.out.println("\nConnection established!");
        createPackets();

        // Neste ponto, todos os pacotes estão criados e prontos para envio ao servidor
        int listIterator = initializeSlowStart(Config.SLOW_START_MAX_DATA_PACKAGES);
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
        int iterator = 0;
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
                for (; iterator < packetsToSend; iterator++) {
                    try {
                        info = packets.get(iterator);
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

            Thread.sleep(Config.DEBUG_TIMEOUT);
            initializeSlowStart(Config.SLOW_START_MAX_DATA_PACKAGES);
        }
        return iterator;
    }

    // Faz a criação dos pacotes e os adiciona à lista de pacotes
    public static void congestionAvoidance(int iterator) throws Exception {
        System.out.println("\nReached congestionAvoidance!\n");

        PacketInfo packetInfo = null;
        PacketResponse response = null;
        List<String> acksReceived = new ArrayList<String>();
        int quantPacketSend = Config.SLOW_START_MAX_DATA_PACKAGES + 1;

        try {
            while (packets.size() != iterator) {
                for (int i = 0; i < quantPacketSend; i++) {
                    try {
                        packetInfo = packets.get(iterator);
                    } catch (Exception ex) {
                        // Terminou a iteração, todos os pacotes foram enviados
                        break;
                    }

                    sendPacket(packetInfo);
                    response = receivePacket();
                    acksReceived.add("Received response: " + response.getMessage() + ":" + response.getSeq());
                    iterator++;
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
            System.out.println("\nTimeout");
            System.out.println("Resending packet...\n");
            initializeSlowStart(Config.SLOW_START_MAX_DATA_PACKAGES);

        }
    }

    public static PacketResponse parseResponseMessage(DatagramPacket message) {
        String[] split = new String(message.getData()).split(Config.MESSAGE_SPLITTER);
        if (split[0].trim().equals(Config.FINISHED)) {
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
        Thread.sleep(Config.DEBUG_TIMEOUT);
    }

    public static PacketResponse receivePacket() throws Exception {
        byte[] responseData = new byte[1024];
        DatagramPacket receivePacket = new DatagramPacket(responseData, responseData.length, inetAddress, port);
        datagramSocket.receive(receivePacket);
        return parseResponseMessage(receivePacket);
    }

    public static void createPackets() throws Exception {
        // Se estiver usando execução MOCK, defina este valor como o próximo número de
        // sequência a ser enviado.
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
            byte[] packetData = new byte[Config.PACKET_SIZE];

            // Preenche o pacote com dados do arquivo
            for (int i = 0; i < Config.PACKET_SIZE; i++) {
                bytesRead++;
                if (offset < fileBytes.length) {
                    packetData[i] = fileBytes[offset++];
                } else {
                    // Preenchimento no último pacote, se necessário
                    packetData[i] = 0; // por exemplo, preenche com zero
                }
            }

            PacketInfo packet = new PacketInfo(packetData, Utils.calculateCRC(packetData), sequenceNumber);
            packet.setFinalPacket(bytesRead >= fileSize);
            packets.add(packet);
            sequenceNumber++;
        }

    }

    public static byte[] insertRandomError(byte[] packetData, double errorProbability, int seq) {
        if (seq == 1)
            return packetData;
        double randomValue = Math.random();
        Random random = new Random();
        if (randomValue < errorProbability) {
            int randomIntInByte = random.nextInt(127);
            byte randomByte = (byte) (randomIntInByte & 0xFF);
            packetData[0] = randomByte;
            System.out.println("Error inserted in packet with seq: " + seq);
            System.out.println();
        }
        return packetData;
    }
}