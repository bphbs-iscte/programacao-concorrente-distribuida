import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author bernardosantos
 */
public class StorageNode {

    private static final int FILE_SIZE = 1000000;
    private static final int BLOCK_SIZE = 100;
    private static final int MAX_BLOCKS = 10000;

    private final InetAddress serverAddress;
    private final String path;
    private final int senderPort, receiverPort;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private final CloudByte[] fileContent = new CloudByte[FILE_SIZE];
    private final List<String> nodes = new ArrayList<>();
    private final BlockingQueue<ByteBlockRequest> queue = new ArrayBlockingQueue<>(MAX_BLOCKS);

    public StorageNode(InetAddress serverAddress, int senderPort, int receiverPort, String path){
        this.serverAddress = serverAddress;
        this.senderPort = senderPort;
        this.receiverPort = receiverPort;
        this.path = path;
    }

    public BlockingQueue<ByteBlockRequest> getQueue() {
        return queue;
    }

    public CloudByte[] getFileContent(){
        return fileContent;
    }

    public static void main(String[] args) throws UnknownHostException {
        try {
            if (args.length == 3)
                new StorageNode(InetAddress.getByName(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]), null).runNode();
            else if (args.length == 4)
                new StorageNode(InetAddress.getByName(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]), args[3]).runNode();
            else throw new IllegalArgumentException("Number of arguments invalid");
        } catch (NumberFormatException e){
            throw new NumberFormatException("Inserted port(s) invalid");
        } catch (UnknownHostException e1) {
            throw new UnknownHostException("Inserted IP invalid");
        } catch (Exception e2) {
            System.err.println("Problem in the arguments: Directory port and address must be written, " +
                    "followed by the node port and the data file name.");
            e2.printStackTrace();
        }
    }

    /**
     * Atrav??s deste m??todo s??o chamados todos os m??todos necess??rios ao funcionamento da classe.
     * Algumas das verifica????es s??o tamb??m feitas neste m??todo.
     */
    private void runNode() {
        try{
            connectToTheDirectory();
            if(!registerInTheDirectory()) {
                System.err.println("Client already enrolled. Try changing port number.");
                return;
            }
            if (path!=null)
                downloadFileContent();
            else {
                setNodeList();
                if(nodes.size() != 0) {
                    createQueue();
                    receiveFileFromNodes();
                }
                else{
                    System.err.println("No nodes available beside yours.");
                    return;
                }
            }
            new UserInput(this).start();
            System.err.println("Accepting connections...");
            startErrorAnalysis();
            waitAndSendData();
        }catch (IOException | InterruptedException | ClassNotFoundException e){
            e.printStackTrace();
        }
    }

    /**
     * Neste m??todo ?? criada a liga????o do n?? ao diret??rio.
     * S??o tamb??m criados canais de comunica????o de texto.
     * Nota: M??todo instanciado pelo runNode().
     */
    private void connectToTheDirectory() throws IOException {
        socket = new Socket(serverAddress,senderPort);
        out = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream())),
                true);
        in = new BufferedReader(new InputStreamReader(
                socket.getInputStream()));
    }

    /**
     * Nesta fun????o ?? feita a inscri????o do n?? no diret??rio.
     * @return Retorna true se o n?? for registado com sucesso (mensagem recebida pelo diret??rio).
     * Nota: Fun????o instanciada pelo runNode().
     */
    private boolean registerInTheDirectory() throws IOException {
        out.println("INSC " + socket.getLocalAddress().getHostAddress() + " " + receiverPort);
        System.err.println("Sending to directory: INSC " + socket.getLocalAddress().getHostAddress() + " " + receiverPort);
        return in.readLine().equals("true");
    }

    /**
     * Nesta fun????o o ficheiro dado ?? lido e convertido num vetor de CloudByte.
     * Esta fun????o apenas ?? instanciada caso o caminho para o ficheiro bin for dado e o
     * n?? se tenha conseguido inscrever no diret??rio.
     */
    private void downloadFileContent() throws IOException {
        try {
            byte[] fileContentsTemp = Files.readAllBytes(new File(path).toPath());
            if (fileContentsTemp.length != FILE_SIZE) throw new IOException();
            for (int i = 0; i < fileContentsTemp.length; i++)
                fileContent[i] = new CloudByte(fileContentsTemp[i]);
            System.err.println("Loaded data from file: " + fileContent.length);
        } catch (IOException e){
            throw new IOException("File not valid. Problem in the path or in the file content.");
        }
    }

    /**
     * Neste m??todo faz-se um pedido ao diret??rio dos n??s atualmente inscritos.
     * ?? criada uma lista de n??s inscritos no diret??rio (excluindo o pr??prio).
     * Este m??todo apenas ?? instanciado caso o caminho para o ficheiro bin for null e o
     * n?? se tenha conseguido inscrever no diret??rio.
     * Nota: M??todo instanciado pelo runNode().
     */
    private void setNodeList() throws IOException {
        nodes.clear();
        System.err.println("Querying directory for other nodes...");
        out.println("nodes");
        String line = in.readLine();
        while (!line.equals("end")) {
            System.err.println("Got answer: " + line);
            if (!line.equals("node " + socket.getLocalAddress().getHostAddress() + " " + receiverPort))
                nodes.add(line);
            line = in.readLine();
        }
    }

    /**
     * Neste m??todo s??o adicionados os pedidos necess??rios BlockingQueue para poss??vel receber o
     * conte??do total do ficheiro presente nos outros n??s inscritos no diret??rio.
     * Este m??todo apenas ?? instanciado caso o caminho para o ficheiro bin for null,
     * se o n?? se tenha conseguido inscrever no diret??rio e
     * se a lista de n??s do diret??rio for diferente de zero.
     * Nota: M??todo instanciado pelo runNode().
     */
    private void createQueue() throws InterruptedException {
        for(int i = 0; i < FILE_SIZE; i += BLOCK_SIZE)
            queue.put(new ByteBlockRequest(i, BLOCK_SIZE));
    }

    /**
     * Neste m??todo s??o enviados pedidos aos n??s consoante o conte??do da BlockingQueue
     * Este m??todo apenas ?? instanciado caso o caminho para o ficheiro .bin for null,
     * se o n?? se tenha conseguido inscrever no diret??rio,
     * se a lista de n??s presentes no diret??rio for diferente de zero e
     * ap??s ter sido atualizada a lista de pedidos a fazer aos outros n??s.
     * Nota: M??todo instanciado pelo runNode().
     */
    private void receiveFileFromNodes() throws ClassNotFoundException, InterruptedException {
        final long time = System.currentTimeMillis();
        CountDownLatch cdl = new CountDownLatch(nodes.size());
        for (String node : nodes) {
            System.err.println("Launching download thread: " + node);
            new DealWithRequest(this,node.split(" ")[1], Integer.parseInt(node.split(" ")[2]), cdl).start();
        }
        cdl.await();
        System.err.println("Elapsed time: " + (System.currentTimeMillis() - time));
    }

    /**
     * A partir deste momento, os n??s est??o prontos a receber pedidos de outros n??s e
     * procuram constantemente por erros no seu ficheiro.
     * Este m??todo apenas ?? instanciado ap??s os n??s terem o conte??do total do ficheiro, quer por via direta,
     * quer atrav??s de outros n??s.
     * Nota: M??todo instanciado pelo runNode().
     */
    private void startErrorAnalysis() {
        Lock lock = new ReentrantLock();
        new CheckForParityErrors(this, 0,lock).start();
        new CheckForParityErrors(this, 1,lock).start();
    }

    /**
     * Neste m??todo o n?? espera por pedidos de blocos de CloudByte e envia sempre que conseguir.
     * ?? instanciado o m??todo checkBlockForErrors para n??o serem enviados blocos com erros de paridade.
     * Nota: M??todo instanciado pelo runNode().
     */
    private void waitAndSendData() throws IOException {
        ServerSocket serverSocket = new ServerSocket(receiverPort);
        while(true){
            Socket getContentSocket = serverSocket.accept();
            ObjectOutputStream out = new ObjectOutputStream(getContentSocket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(getContentSocket.getInputStream());
            while(true) {
                try {
                    ByteBlockRequest request = (ByteBlockRequest) in.readObject();
                    int requestSize = request.getLength();
                    CloudByte[] block = new CloudByte[requestSize];
                    for (int i = 0; i < requestSize; i++)
                        block[i] = fileContent[i + request.getStartIndex()];
                    if(checkBlockForErrors(block)) out.writeObject(block);
                    else out.writeObject(null);
                }catch(Exception e) {
                    break;
                }
            }
            getContentSocket.close();
            out.close();
            in.close();
        }
    }

    /**
     * Este m??todo verifica a exist??ncia de erros nos blocos a enviar aos n??s de
     * forma a impedir o envio de bytes errados.
     * Nota: M??todo instanciado no m??todo acceptingConnections.
     */
    private boolean checkBlockForErrors(CloudByte[] block){
        for(int i = 0; i != block.length; i++)
            if(!block[i].isParityOk())
                return false;
        return true;
    }

    /**
     * M??todo que adiciona o ???byte??? em causa ?? lista de pedidos e despoleta a corre????o do erro
     * Limita????o: Apenas ?? poss??vel corrigir um erro de cada vez.
     * Nota: M??todo instanciado pela classe CheckForParityErrors caso seja detetado algum erro.
     */
    void triggerErrorCorrection(int position) throws IOException, ClassNotFoundException, InterruptedException {
        try {
            System.err.println("Data Maintenance: Error was detected at " + position + ": " + fileContent[position]);
            setNodeList();
            if (nodes.size() >= 2) {
                for (int i = 0; i != nodes.size(); i++)
                    queue.add(new ByteBlockRequest(position, 1));
            } else {
                System.err.println("Cannot correct the error. Insufficient number of nodes." +
                        "\nDisconnecting node.");
                System.exit(-1);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Neste m??todo s??o lan??adas threads usadas para receber os valores corretos do ???byte??? errado.
     * Caso sejam recebidos dois valores iguais, o ???byte??? ?? corrigido para o novo valor.
     * M??todo de corre????o de erros.
     * Limita????o: Pressup??e-se que os ???bytes??? recebidos s??o sempre iguais.
     * Nota: M??todo instanciado pela thread CheckForParityErrors.
     */
    void correctError(int position) throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(2);
        BlockingQueue<CloudByte> correction = new ArrayBlockingQueue<>(nodes.size());
        for (String node : nodes) {
            System.err.println("Launching correction thread: " + node);
            new DealWithError(this, node.split(" ")[1], Integer.parseInt(node.split(" ")[2]), cdl, correction).start();
        }
        cdl.await();
        CloudByte b1 = correction.take();
        CloudByte b2 = correction.take();
        if(b1.equals(b2)) {
            fileContent[position] = b1;
            System.err.println("Corrected to: " + fileContent[position]+
                    "\nContinuing error analysis...");
        }
    }

    /**
     * Inje????o local do erro para posterior corre????o pelas threads de procura de erros.
     * Nota: M??todo instanciado na classe UserInput
     */
    void injectError(int position){
        if(position >= 0 && position <= 999999) {
            fileContent[position].makeByteCorrupt();
            System.err.println("Error injected: " + fileContent[position]);
        }else System.err.println("Invalid array position.");
    }
}