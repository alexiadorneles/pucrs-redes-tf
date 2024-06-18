import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class Receiver {

    static Map<Integer, byte[]> receivedFileData = new HashMap<>();
    static DatagramSocket serverSocket;
    static InetAddress ipAddress;
    static int port;
    static String outputFilename = "received_file.txt";

    public static void main(String args[]) throws Exception {
        System.out.println("Started");

        // Inicializa o socket na porta 9876
        serverSocket = new DatagramSocket(Config.SENDER_PORT);
        int finalPacketSeqNumber;

        while (true) {
            Thread.sleep(50);
            PacketInfo packetInfo = receivePacket();

            if (packetInfo.isFinalPacket()) {
                receivedFileData.put(packetInfo.getSeq(), packetInfo.getFileData());
                // Guarda o número de sequência do último pacote
                finalPacketSeqNumber = packetInfo.getSeq();
                int missingPacket = 0;

                do {
                    // Checa por pacotes ausentes após o último pacote recebido
                    missingPacket = checkMissingPackets(finalPacketSeqNumber);

                    if (missingPacket != 0) {
                        System.out.println("Missing packets detected, requesting again...");

                        // Solicita o pacote perdido novamente
                        sendResponsePacket("ACK" + Config.MESSAGE_SPLITTER + missingPacket, ipAddress, port);
                        packetInfo = receivePacket();
                        validatePacketCrc(packetInfo);

                        // Insere o pacote recebido no mapa de dados
                        if (packetInfo.getSeq() == missingPacket) {
                            receivedFileData.put(packetInfo.getSeq(), packetInfo.getFileData());
                            continue;
                        }
                    }

                } while (missingPacket != 0);

                sendResponsePacket(Config.FINISHED, ipAddress, port);
                System.out.println("All packets received! Disconnecting client...");
                buildAndValidateFile(finalPacketSeqNumber);
                break;
            }

            // Calcula o CRC do pacote
            validatePacketCrc(packetInfo);

            // Armazena o pacote recebido no mapa
            receivedFileData.put(packetInfo.getSeq(), packetInfo.getFileData());

            // Verifica se há pacotes faltando
            int missingPacket = checkMissingPackets(packetInfo.getSeq());

            if (missingPacket != 0) {
                // Solicita o pacote perdido novamente
                sendResponsePacket("ACK" + Config.MESSAGE_SPLITTER + missingPacket, ipAddress, port);
                System.out.println("Missing packets detected, requesting again...");
                continue;
            }

            // Tudo certo, pacote recebido, envia resposta e espera o próximo
            packetInfo.setSeq(packetInfo.getSeq() + 1);
            System.out.println("Success, waiting for new packets...");

            sendResponsePacket("ACK" + Config.MESSAGE_SPLITTER + packetInfo.getSeq(), ipAddress, port);
        }
    }

    private static void validatePacketCrc(PacketInfo packetInfo) {
        long crc = Utils.calculateCRC(packetInfo.getFileData());
        if (crc == packetInfo.getCRC()) {
            System.out.println("Correct CRC, intact packet");
        } else {
            System.out.println("Elements lost in packet path");
        }
    }

    public static void sendResponsePacket(String message, InetAddress ipAddress, int port) throws Exception {
        byte[] sendData = new byte[1024];
        DatagramPacket response = new DatagramPacket(sendData, sendData.length, ipAddress, port);
        response.setData(message.getBytes());
        serverSocket.send(response);
    }

    public static PacketInfo receivePacket() throws Exception {
        byte[] receiveData = new byte[10024];

        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        serverSocket.receive(receivePacket);

        // Converte os bytes em uma string
        String receivedMessage = new String(receivePacket.getData());
        ipAddress = receivePacket.getAddress();
        port = receivePacket.getPort();

        PacketInfo packetInfo = parseInputMessage(receivedMessage);

        String message = formatByteToString(packetInfo.getFileData());
        System.out.println(System.lineSeparator());
        System.out.println("Received message: " + message);
        System.out.println("CRC: " + packetInfo.getCRC());
        System.out.println("Sequence number: " + packetInfo.getSeq());
        System.out.println("Is the last packet: " + packetInfo.isFinalPacket());
        System.out.println(System.lineSeparator());

        return packetInfo;
    }

    public static int checkMissingPackets(int seqReceived) {
        // Obtém todos os números de sequência antes do atual
        List<Integer> list = receivedFileData.keySet()
                .stream()
                .filter(seq -> seq <= seqReceived)
                .collect(Collectors.toList());

        // Verifica se os pacotes anteriores ao atual chegaram corretamente
        for (int seq = 1; seq <= seqReceived; seq++) {
            if (seq != list.get(seq - 1)) {
                return seq; // Pacote faltando
            }
        }

        return 0;
    }

    public static PacketInfo parseInputMessage(String message) {
        PacketInfo packetInfo = new PacketInfo();

        String[] splitMessage = message.split(Config.MESSAGE_SPLITTER);
        System.out.println("Received packet of seq " + Integer.parseInt(splitMessage[2].trim()));

        packetInfo.setFileData(formatByteArray(splitMessage[0]));
        packetInfo.setCRC(Long.parseLong(splitMessage[1]));
        packetInfo.setSeq(Integer.parseInt(splitMessage[2].trim()));

        try {
            // Define se é o último pacote
            packetInfo.setFinalPacket(Boolean.parseBoolean(splitMessage[3].trim()));
        } catch (IndexOutOfBoundsException ex) {
            // Não é o último pacote
        }
        return packetInfo;
    }

    // Converte a string recebida para array de bytes
    public static byte[] formatByteArray(String message) {
        String initial = message
                .replace("[", "")
                .replace("]", "")
                .replace(" ", "");

        String[] size = initial.split(",");
        byte[] auxArray = new byte[size.length];
        for (int i = 0; i < size.length; i++) {
            auxArray[i] = Byte.parseByte(size[i]);
        }

        return auxArray;
    }

    public static String formatByteToString(byte[] message) {
        return new String(message, StandardCharsets.UTF_8);
    }

    public static void buildAndValidateFile(int finalPacketSeqNumber) throws Exception {
        // Lista auxiliar para armazenar os bytes do arquivo sem o padding
        List<Byte> fileBytes = new ArrayList<>();

        // Percorre os dados recebidos para montar o arquivo completo
        for (int seq = 1; seq <= finalPacketSeqNumber; seq++) {
            if (!receivedFileData.containsKey(seq))
                continue;
            byte[] packetData = receivedFileData.get(seq);

            // Verifica se é o último pacote para determinar o tamanho real dos dados
            int dataSize = (seq == finalPacketSeqNumber) ? getRealDataSize(packetData) : packetData.length;

            // Adiciona os dados válidos do pacote à lista
            for (int i = 0; i < dataSize; i++) {
                fileBytes.add(packetData[i]);
            }
        }

        // Converte a lista de bytes para um array de bytes
        byte[] allFileBytes = new byte[fileBytes.size()];
        for (int i = 0; i < fileBytes.size(); i++) {
            allFileBytes[i] = fileBytes.get(i);
        }

        // Escreve os bytes no arquivo final
        FileOutputStream fileOutputStream = new FileOutputStream(outputFilename);
        fileOutputStream.write(allFileBytes);
        fileOutputStream.close();

        System.out.println("File received and saved successfully: " + outputFilename);
    }

    // Função para obter o tamanho real dos dados no último pacote
    private static int getRealDataSize(byte[] packetData) {
        for (int i = packetData.length - 1; i >= 0; i--) {
            if (packetData[i] != 0) {
                return i + 1; // Retorna o índice do último byte não nulo + 1
            }
        }
        return 0; // Caso todos os bytes sejam nulos
    }
}