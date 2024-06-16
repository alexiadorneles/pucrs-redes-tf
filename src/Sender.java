import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

public class Sender {

    static List<PacketInfo> packets = new ArrayList<>();

    static final int SLOW_START_MAX_DATA_PACKAGES = 2;
    static final char FILE_END_DELIMITER_CHAR = '|';

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
        String[] split = new String(message.getData()).split("-");

        if (split[0].trim().equals("FINISHED")) {
            // nao importa o seq aqui, pq é o ultimo pacote do server
            return new PacketResponse(split[0], 1);
        }

        return new PacketResponse(split[0], Integer.parseInt(split[1].trim()));
    }

    public static void sendPacket(PacketInfo packet) throws Exception {
        String message = "";

        if (packet.isFinalPacket()) {
            message = Arrays.toString(packet.getFileData()) + "-" + packet.getCRC() + "-" + packet.getSeq() + "-"
                    + packet.isFinalPacket();
        } else {
            message = Arrays.toString(packet.getFileData()) + "-" + packet.getCRC() + "-" + packet.getSeq();
        }

        System.out.println("enviando mensagem: " + message);

        byte[] packetData = message.getBytes();

        DatagramPacket sendPacket = new DatagramPacket(packetData, packetData.length, address, port);

        socket.send(sendPacket);
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
        // lê o caminho do arquivo.
        Path path = Paths.get(filename);

        // monta uma lista com todas as linhas
        List<String> fileContent = Files.readAllLines(path);

        // caso utilizar execuçao em MOCK, colocar esse valor como o proximo seq number
        // a ser enviado.
        int numeroSequencia = 1;

        // coloca na lista de dados de cada packet o que deve ser enviado, em ordem
        // IMPORTANTE: esse método leva em conta que todas linhas do arquivo possuem 300
        // bytes (300 caracteres), assim como é visto no case1, dentro da folder input,
        // comportamentos inesperados podem ocorrer caso essa condiçao nao seja
        // verdadeira.
        for (int i = 0; i < fileContent.size(); i++) {

            String content = fileContent.get(i);
            System.out.println(content.toCharArray());
            final int MAX_BYTES = 300;

            if (content.toCharArray().length < MAX_BYTES) {
                char[] contentBytes = new char[MAX_BYTES];
                char[] contentChars = content.toCharArray();

                for (int j = 0; j < contentChars.length; j++) {
                    contentBytes[j] = contentChars[j];
                }

                System.out.println(content.toCharArray());

                // Este método adiciona delimiters para os ultimos pacotes que nao tem 300 bytes
                // terem delimitador
                for (int j = contentChars.length; j < MAX_BYTES; j++) {
                    contentBytes[j] = FILE_END_DELIMITER_CHAR;
                }

                content = new String(contentBytes);

            }

            byte[] arrayBytes = content.getBytes();

            // realizando calculo do CRC
            long crc = calculaCRC(arrayBytes);

            PacketInfo packet = new PacketInfo(arrayBytes, crc, numeroSequencia);

            // Aqui definimos o pacote final a ser enviado
            if (fileContent.size() - 1 == i) {
                packet.setFinalPacket(true);
            }

            packets.add(packet);

            numeroSequencia++;
        }
    }
}