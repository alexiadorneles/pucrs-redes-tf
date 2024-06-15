import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.*;
import java.util.zip.CRC32;

public class Sender {

    static List<PacketInfo> packets = new ArrayList<>();

    static final int SLOW_START_MAX_DATA_PACKAGES = 2;
    static final char FILE_END_DELIMITER_CHAR = '|';

    private static String ipAddress;
    private static InetAddress address;
    private static int port;
    private static String filename;

    static Map<Integer, Integer> acksDuplicados = new HashMap<>();

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

                    checkDuplicatedAck(response, packetInfo.getSeq());

                    acksReceived.add("recebe response: " + response.getMessage() + ":" + response.getSeq());

                    listIterator++;
                }

                for (int i = 0; i < acksReceived.size(); i++) {
                    System.out.println(acksReceived.get(i));
                }

                acksReceived = new ArrayList<String>();

                quantPacketSend++;
            }

            String finalServerResponse = response.getMessage().trim();

            if (packetInfo.isFinalPacket()) {
                while (!finalServerResponse.equals("FINISHED")) {
                    System.out.println("Pacotes faltando, entrando em contato com o servidor para verificar...");

                    finalServerResponse = sendLastMissingPackets();
                }
            }

        } catch (SocketTimeoutException ex) {

            for (int i = 0; i < acksReceived.size(); i++) {
                System.out.println(acksReceived.get(i));
            }

            acksReceived = new ArrayList<String>();

            System.out.println("Timeout");
            System.out.println("Reenviando pacote...");

            acksDuplicados.clear();
            initializeSlowStart(SLOW_START_MAX_DATA_PACKAGES);

        }
    }

    public static void checkDuplicatedAck(PacketResponse response, int seqSent) throws Exception {
        System.out.println("ACK duplicado, problema detectado...");
        if (seqSent != response.getSeq() - 1) {

            int duplicado = response.getSeq();

            if (!acksDuplicados.containsKey(duplicado)) {
                acksDuplicados.put(duplicado, 1);
            } else {
                acksDuplicados.put(duplicado, acksDuplicados.get(duplicado) + 1);
            }

            List<Integer> packetsLostSeqNumber = acksDuplicados.entrySet().stream()
                    // se ja tiver 3 ou mais acks na lista...
                    .filter(x -> x.getValue() >= 3)
                    // pega a key (seq do pacote perdido)...
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            if (!packetsLostSeqNumber.isEmpty()) {
                // value aqui é o seq do pacote perdido
                for (int seq : packetsLostSeqNumber) {
                    PacketInfo packet = packets
                            .stream()
                            .filter(x -> x.getSeq() == seq)
                            .findFirst()
                            .orElseThrow(() -> new Exception("Nao foi encontrado o pacote que falhou no envio"));

                    // UTILIZADO APENAS PARA DADOS MOCKADOS
                    if (packet == null) {
                        if (seq == 4) {
                            packet = new PacketInfo(new byte[] { 4, 4, 4, 4 }, 123453252, seq);
                        }

                        if (seq == 5) {
                            packet = new PacketInfo(new byte[] { 5, 5, 5, 5 }, 123453252, seq);
                        }

                        if (seq == 6) {
                            packet = new PacketInfo(new byte[] { 6, 6, 6, 6 }, 123453252, seq);
                        }

                        if (seq == 7) {
                            packet = new PacketInfo(new byte[] { 7, 7, 7, 7 }, 123453252, seq);
                        }

                        if (seq == 11) {
                            packet = new PacketInfo(new byte[] { 11, 11, 11, 11 }, 123453252, seq);
                        }

                        if (seq == 12) {
                            packet = new PacketInfo(new byte[] { 12, 12, 12, 12 }, 123453252, seq);
                        }
                    }

                    System.out.println("REENVIANDO PACOTE QUE FOI PERDIDO - SEQ[" + duplicado + "]");

                    sendPacket(packet);

                    System.out.println("PACOTE QUE HAVIA FALHADO RECEBIDO COM SUCESSO!");

                    // removendo que este pacote da lista de pacotes perdidos
                    acksDuplicados.remove(seq);
                }
            }
        }
    }

    // método responsavel por enviar pacotes que tenham falhado pouco antes do
    // ultimo pacote ser enviado
    public static String sendLastMissingPackets() throws Exception {
        PacketResponse newResponse = null;

        List<Integer> packetsLostSeqNumber = acksDuplicados.entrySet().stream()
                // se ja tiver 1 ou mais acks na lista...
                .filter(x -> x.getValue() >= 1)
                // pega a key (seq do pacote perdido)...
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (!packetsLostSeqNumber.isEmpty()) {

            // seq aqui é o seq do pacote perdido
            for (int seq : packetsLostSeqNumber) {
                PacketInfo packet = packets
                        .stream()
                        .filter(x -> x.getSeq() == seq)
                        .findFirst()
                        .orElseThrow(() -> new Exception("Nao foi encontrado o pacote que falhou no envio."));

                // PARA UTILIZAR DADOS MOCKADOS, NaO É NECESSARIO PARA EXECUÇaO FINAL
                if (packet == null) {
                    if (seq == 1) {
                        packet = new PacketInfo(new byte[] { 1, 1, 1, 1 }, 123453252, seq);
                    }

                    if (seq == 2) {
                        packet = new PacketInfo(new byte[] { 2, 2, 2, 2 }, 123453252, seq);
                    }

                    if (seq == 3) {
                        packet = new PacketInfo(new byte[] { 3, 3, 3, 3 }, 123453252, seq);
                    }

                    if (seq == 4) {
                        packet = new PacketInfo(new byte[] { 4, 4, 4, 4 }, 123453252, seq);
                    }

                    if (seq == 5) {
                        packet = new PacketInfo(new byte[] { 5, 5, 5, 5 }, 123453252, seq);
                    }

                    if (seq == 6) {
                        packet = new PacketInfo(new byte[] { 6, 6, 6, 6 }, 123453252, seq);
                    }

                    if (seq == 7) {
                        packet = new PacketInfo(new byte[] { 7, 7, 7, 7 }, 123453252, seq);
                    }

                    if (seq == 8) {
                        packet = new PacketInfo(new byte[] { 8, 8, 8, 8 }, 123453252, seq);
                    }

                    if (seq == 9) {
                        packet = new PacketInfo(new byte[] { 9, 9, 9, 9 }, 123453252, seq);
                    }

                    if (seq == 10) {
                        packet = new PacketInfo(new byte[] { 10, 10, 10, 10 }, 123453252, seq);
                    }

                    if (seq == 11) {
                        packet = new PacketInfo(new byte[] { 11, 11, 11, 11 }, 123453252, seq);
                    }

                    if (seq == 12) {
                        packet = new PacketInfo(new byte[] { 12, 12, 12, 12 }, 123453252, seq);
                    }
                }

                System.out.println("REENVIANDO PACOTE QUE FOI PERDIDO - SEQ[" + seq + "]");

                sendPacket(packet);

                newResponse = receivePacket();

                if (!newResponse.getMessage().trim().equals("FINISHED")) {
                    acksDuplicados.remove(seq);
                    acksDuplicados.put(newResponse.getSeq(), 3);

                    sendLastMissingPackets();
                }

                System.out.println("PACOTE QUE HAVIA FALHADO RECEBIDO COM SUCESSO!");

                // removendo que este pacote foi perdido
                acksDuplicados.remove(seq);

                return newResponse.getMessage();
            }
        }

        return "FINISHED";
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