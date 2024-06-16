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
    private static InetAddress address;
    private static int port;
    private static String filename;

    static DatagramSocket socket;

    public static void main(String[] args) throws Exception {
        ipAddress = "127.0.0.1"; // Endereço IP do destinatário
        port = 9876; // Porta utilizada na comunicaçao
        filename = "file.txt"; // Nome do arquivo a ser transferido

        startConnection();
    }

    public static void startConnection() throws Exception {
        address = InetAddress.getByName(ipAddress);

        socket = new DatagramSocket();
        System.out.println("Iniciou a conexao do sender...");

        // Definindo timeout pro socket (neste caso é 3 segundos)
        socket.setSoTimeout(3 * 1000);

        System.out.println("\nConexao estabelecida!");

        createPackets();

        // neste momento, temos todos os pacotes criados, tudo pronto pra enviar para o
        // server
        int listIterator = initializeSlowStart(SLOW_START_MAX_DATA_PACKAGES);

        if (listIterator >= packets.size()) {
            System.out.println("tudo enviado, nao precisa do avoidance...");
        } else {
            congestionAvoidance(listIterator);
            System.out.println("\nConexao encerrada!");
        }
    }

    public static int initializeSlowStart(int packageLimit) throws Exception {
        System.out.println();
        int pacotesParaEnviar = 1;

        int listIterator = 0;

        int actualPackageLimit = 1;
        int packetCalculo = 1;

        // calcula o limite de pacotes que pode enviar
        while (packetCalculo != packageLimit) {
            packetCalculo *= 2;
            actualPackageLimit = actualPackageLimit * 2 + 1;
        }

        List<String> acksReceived = new ArrayList<String>();

        PacketInfo info;

        // envia os pacotes
        try {
            while (pacotesParaEnviar <= actualPackageLimit) {
                for (listIterator = listIterator; listIterator < pacotesParaEnviar; listIterator++) {
                    try {
                        info = packets.get(listIterator);
                    } catch (Exception ex) {
                        // acabou de iterar, enviou tudo
                        break;
                    }

                    sendPacket(info);

                    PacketResponse response = receivePacket();

                    acksReceived.add("recebe response: " + response.getMessage() + ":" + response.getSeq());

                }

                for (int i = 0; i < acksReceived.size(); i++) {
                    System.out.println(acksReceived.get(i));
                }

                acksReceived = new ArrayList<String>();

                pacotesParaEnviar = pacotesParaEnviar * 2 + 1;
            }
        } catch (SocketTimeoutException ex) {
            for (int i = 0; i < acksReceived.size(); i++) {
                System.out.println(acksReceived.get(i));
            }

            acksReceived = new ArrayList<String>();

            System.out.println("Timeout");
            System.out.println("Reenviando pacote...");

            Thread.sleep(DEBUG_TIMEOUT);
            initializeSlowStart(SLOW_START_MAX_DATA_PACKAGES);
        }
        return listIterator;
    }

    // cria os pacotes e adiciona na lista de pacotes
    public static void congestionAvoidance(int listIterator) throws Exception {
        System.out.println("Chegou no congestionAvoidance!");

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
                        // acabou de iterar, enviou tudo
                        break;
                    }

                    sendPacket(packetInfo);
                    response = receivePacket();
                    acksReceived.add("recebe response: " + response.getMessage() + ":" + response.getSeq());

                    listIterator++;
                }

                for (int i = 0; i < acksReceived.size(); i++) {
                    System.out.println(acksReceived.get(i));
                }

                acksReceived = new ArrayList<String>();

                quantPacketSend++;
            }

            /*
             * Desconfio que isso não seja necessário pro nosso trabalho, comentando pra
             * checar depois
             */
            /*
             * mas a lógica dependia de ACKs duplicados, que não está no nosso escopo de
             * trabalho
             */
            // String finalServerResponse = response.getMessage().trim();

            // if (packetInfo.isFinalPacket()) {
            // while (!finalServerResponse.equals("FINISHED")) {
            // System.out.println("Pacotes faltando, entrando em contato com o servidor para
            // verificar...");

            // finalServerResponse = sendLastMissingPackets();
            // }
            // }

        } catch (SocketTimeoutException ex) {

            for (int i = 0; i < acksReceived.size(); i++) {
                System.out.println(acksReceived.get(i));
            }

            acksReceived = new ArrayList<String>();

            System.out.println("Timeout");
            System.out.println("Reenviando pacote...");

            initializeSlowStart(SLOW_START_MAX_DATA_PACKAGES);

        }
    }

    public static PacketResponse parseResponseMessage(DatagramPacket message) {
        String[] split = new String(message.getData()).split(Config.MESSAGE_SPLITTER);

        if (split[0].trim().equals("FINISHED")) {
            // nao importa o seq aqui, pq é o ultimo pacote do server
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
        System.out.println("enviando mensagem: " + message);
        byte[] packetData = message.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(packetData, packetData.length, address, port);
        socket.send(sendPacket);
        System.out.println("pacote " + packet.getSeq() + " enviado");
        Thread.sleep(DEBUG_TIMEOUT);
    }

    public static PacketResponse receivePacket() throws Exception {
        byte[] responseData = new byte[1024];

        DatagramPacket receivePacket = new DatagramPacket(responseData, responseData.length, address, port);

        socket.receive(receivePacket);

        PacketResponse response = parseResponseMessage(receivePacket);

        return response;
    }

    public static long calculaCRC(byte[] array) {
        CRC32 crc = new CRC32();

        crc.update(array);

        long valor = crc.getValue();

        return valor;
    }

    public static void createPackets() throws Exception {
        // caso utilizar execuçao em MOCK, colocar esse valor como o proximo seq number
        // a ser enviado.
        int numeroSequencia = 1;

        File file = new File(filename);
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
                    // Padding no último pacote, se necessário
                    packetData[i] = 0; // por exemplo, preenche com zero
                }
            }

            // realizando calculo do CRC
            long crc = calculaCRC(packetData);
            PacketInfo packet = new PacketInfo(packetData, crc, numeroSequencia);
            packet.setFinalPacket(bytesRead >= fileSize);
            packets.add(packet);
            numeroSequencia++;
        }

    }

    public static byte[] insertRandomError(byte[] packetData, double errorProbability, int seq) {
        if (seq == 1)
            return packetData;
        // Gerar um número aleatório entre 0 e 1
        double randomValue = Math.random();
        Random random = new Random();

        // Verificar se ocorre um erro com base na probabilidade
        if (randomValue < errorProbability) {
            // Gerar um byte aleatório entre 0 e 255
            int randomIntInByte = random.nextInt(127); // Gera um número entre 0 e 255

            // Converter o número para um byte, garantindo que seja positivo
            byte randomByte = (byte) (randomIntInByte & 0xFF);

            // Simular um erro: por exemplo, modificar o primeiro byte
            packetData[0] = randomByte;

            System.out.println("Erro inserido no pacote de seq: " + seq);
            System.out.println("Pacote modificado: " + Arrays.toString(packetData));
            System.out.println();
        }

        return packetData;
    }

}