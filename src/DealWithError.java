import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * @author bernardosantos
 */
public class DealWithError extends Thread {

    private final StorageNode node;
    private final String inAddress;
    private final int inPort;
    private final CountDownLatch cdl;
    private final List<CloudByte[]> correction;


    public DealWithError(StorageNode node, String inAddress, int inPort, CountDownLatch cdl, List<CloudByte[]> correction) {
        this.node = node;
        this.inAddress = inAddress;
        this.inPort = inPort;
        this.cdl = cdl;
        this.correction = correction;
    }

    @Override
    public void run(){
        ByteBlockRequest request = null;
        try {
            Socket socket = new Socket(inAddress, inPort);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            request = node.queue.take();
            out.writeObject(request);
            try {
                CloudByte[] b = (CloudByte[]) in.readObject();
                correction.add(b);
            }catch(NullPointerException e){
                node.queue.add(request);
                System.err.println("Parity error. Last request added to the queue: " + request);
                e.printStackTrace();
            }
            out.close();
            in.close();
            socket.close();
        } catch (IOException | InterruptedException | ClassNotFoundException e) {
            if(request != null) {
                node.queue.add(request);
                System.err.println("Last request added to the queue : " + request);
            }
            System.err.println("Unable to connect to desired socket: " + inAddress + " " + inPort);
            e.printStackTrace();
        }
        cdl.countDown();
    }
}