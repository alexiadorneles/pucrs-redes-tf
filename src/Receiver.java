import java.io.FileOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

class Receiver {

    static Map<Integer, byte[]> receivedFileData = new HashMap<>();
    static DatagramSocket serverSocket;
    static InetAddress ipAddress;
    static int port;
    static String outputFilename = "received_file.txt";

    public static void main(String args[]) throws Exception {
        System.out.println("Iniciou");

        // estabelecendo que esse socket roda na porta 9876
        serverSocket = new DatagramSocket(9876);

        int finalPacketSeqNumber;

        while (true) {

            PacketInfo packetInfo = receivePacket();

            // tratamento para o ultimo pacote recebido
            if (packetInfo.isFinalPacket()) {

                receivedFileData.put(packetInfo.getSeq(), packetInfo.getFileData());

                // ultimo packet recebido
                finalPacketSeqNumber = packetInfo.getSeq();

                int missingPacket = 0;

                do {
                    // valida se tem algum pacote faltando após ter recebido o ultimo
                    missingPacket = checkMissingPackets(finalPacketSeqNumber);

                    if (!(missingPacket == 0)) {
                        System.out.println("Está no pacote final e há pacotes faltando, requisitando novamente...");

                        // missingPacket contém o seq do pacote perdido
                        // server deve requisitar ele novamente
                        sendResponsePacket("ACK" + Config.MESSAGE_SPLITTER + missingPacket, ipAddress, port);

                        packetInfo = receivePacket();

                        // calcula o CRC do pacote
                        long crc = calculaCRC(packetInfo.getFileData());

                        if (crc == packetInfo.getCRC()) {
                            System.out.println("CRC correto, pacote chegou íntegro");
                        } else {
                            System.out.println("Elementos perdidos no caminho do pacote");
                        }

                        // se recebeu o packet certo, insere no dicionario com o conteudo do arquivo
                        if (packetInfo.getSeq() == missingPacket) {
                            receivedFileData.put(packetInfo.getSeq(), packetInfo.getFileData());
                            continue;
                        }
                    }

                } while (missingPacket != 0);

                // após ter recebido tudo, envia um FINISHED para o client e desconecta
                sendResponsePacket("FINISHED", ipAddress, port);

                System.out.println("TERMINOU DE RECEBER TODOS PACOTES! DESCONECTANDO CLIENT....");

                // começa a manipular os bytes recebidos para salvar o arquivo
                buildAndValidateFile(finalPacketSeqNumber);

                break;
            }

            // calcula o CRC do pacote
            long crc = calculaCRC(packetInfo.getFileData());

            if (crc == packetInfo.getCRC()) {
                System.out.println("CRC correto, pacote chegou integro");
            } else {
                System.out.println("Elementos perdidos no caminho do pacote");
            }

            // insere no dicionario de pacotes recebidos os dados desse arquivo, com chave =
            // seq number
            receivedFileData.put(packetInfo.getSeq(), packetInfo.getFileData());

            // após receber o pacote, verifica se há pacotes faltando
            int missingPacket = checkMissingPackets(packetInfo.getSeq());

            if (!(missingPacket == 0)) {
                // missingPacket contém o pacote perdido
                // server deve requisitar ele novamente
                sendResponsePacket("ACK" + Config.MESSAGE_SPLITTER + missingPacket, ipAddress, port);
                System.out.println("Está no pacote final e há pacotes faltando, requisitando novamente...");

                continue;
            }

            // tudo ok, pacote recebido, envia resposta e espera o próximo
            packetInfo.setSeq(packetInfo.getSeq() + 1);
            System.out.println("Sucesso, aguardando novos pacotes...");

            sendResponsePacket("ACK" + Config.MESSAGE_SPLITTER + packetInfo.getSeq(), ipAddress, port);

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

        // Converter os bytes em uma sequência de caracteres
        String receivedMessage = new String(receivePacket.getData());

        ipAddress = receivePacket.getAddress();

        port = receivePacket.getPort();

        PacketInfo packetInfo = parseInputMessage(receivedMessage);

        String message = formatByteToString(packetInfo.getFileData());
        System.out.println(System.lineSeparator());
        System.out.println("Mensagem recebida: " + message);
        System.out.println("CRC: " + packetInfo.getCRC());
        System.out.println("Número de sequência: " + packetInfo.getSeq());
        System.out.println("É o último pacote: " + packetInfo.isFinalPacket());
        System.out.println(System.lineSeparator());

        return packetInfo;
    }

    /**
     * @param seqReceived sequence number recebido no pacote atual
     * @return 0 se nao há pacotes faltando, senao retorna o sequence number do
     *         pacote faltando
     */
    public static int checkMissingPackets(int seqReceived) {
        // pega todos os seqs antes do que chegou agora
        List<Integer> lista = receivedFileData.keySet()
                .stream()
                .filter(seq -> seq <= seqReceived)
                .collect(Collectors.toList());

        // verifica se os ultimos pacotes antes do que chegou agora chegaram ok..
        for (int seq = 1; seq <= seqReceived; seq++) {
            // se um pacote nao chegou, precisa pedir de novo
            // seq - 1 no lista.get pq os index começam em 0
            if (seq != lista.get(seq - 1)) {
                return seq; // faltou pacote aqui (i tá faltando)

                // se o pacote 1 e 2 tiverem faltando, ele vai pedir o 1 até conseguir receber,
                // pra só depois pedir o 2, e assim em diante
                // como mexe direto no dicionario de dados recebidos, ele vai ficar pedindo o
                // mesmo sempre até receber ele
            }
        }

        return 0;
    }

    public static PacketInfo parseInputMessage(String message) {
        PacketInfo packetInfo = new PacketInfo();

        String[] splitMessage = message.split(Config.MESSAGE_SPLITTER);
        System.out.println(message);
        System.out.println(Arrays.toString(splitMessage));
        System.out.println("Recebeu pacote de seq " + Integer.parseInt(splitMessage[2].trim()));

        packetInfo.setFileData(formatByteArray(splitMessage[0]));
        packetInfo.setCRC(Long.parseLong(splitMessage[1]));
        packetInfo.setSeq(Integer.parseInt(splitMessage[2].trim()));

        try {
            // Dentro de um try-catch pq só no ultimo pacote vem essa info preenchida como
            // true
            packetInfo.setFinalPacket(Boolean.parseBoolean(splitMessage[3].trim()));
        } catch (IndexOutOfBoundsException ex) {
            // é o ultimo pacote...
        }
        return packetInfo;
    }

    // monta o fileData do PacketInfo
    public static byte[] formatByteArray(String message) {
        System.out.println(message);
        String initial = message
                .replace("[", "")
                .replace("]", "")
                .replace(" ", "");

        String[] size = initial.split(",");

        byte[] auxArray = new byte[size.length];

        for (int i = 0; i < size.length; i++) {
            System.out.println(size[i]);
            auxArray[i] = Byte.parseByte(size[i]);
        }

        return auxArray;
    }

    public static String formatByteToString(byte[] message) {
        String messageString = new String(message, StandardCharsets.UTF_8);
        return messageString;
    }

    public static long calculaCRC(byte[] array) {
        CRC32 crc = new CRC32();

        crc.update(array);

        long valor = crc.getValue();

        return valor;
    }

    public static void buildAndValidateFile(int finalPacketSeqNumber) throws Exception {
        // Lista auxiliar para armazenar os bytes do arquivo sem o padding
        List<Byte> fileBytes = new ArrayList<>();

        // Percorre os dados recebidos para montar o arquivo completo
        for (int seq = 1; seq <= finalPacketSeqNumber; seq++) {
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

        System.out.println("Arquivo recebido e salvo com sucesso: " + outputFilename);
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