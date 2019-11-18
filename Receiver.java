/** Datagram receiver.
 *
 *  This class provides a receive interface to a datagram socket.
 *  Specifically, it enables one to test for the presence of an
 *  incoming packet before attempting a (potentially blocking)
 *  receive operation.
 */  

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Receiver implements Runnable {
	private Thread myThread;	// thread that executes run() method

	private DatagramSocket sock;
	private ArrayBlockingQueue<Pair<Packet,Integer>> rcvq;
	private int debug;

	private ArrayList<NborInfo> nborList;

	Receiver(DatagramSocket sock, ArrayList<NborInfo> nborList, int debug) {
		this.sock = sock; this.nborList = nborList;
		this.debug = debug;

		// initialize queue for received packets
		// stores both the packet and socket address of the sender
		rcvq = new ArrayBlockingQueue<Pair<Packet,Integer>>(1000,true);
	}

	/** Instantiate run() thread and start it running. */
	public void start() {
		myThread = new Thread(this); myThread.start();
	}

	/** Wait for thread to quit. */
	public void join() throws Exception { myThread.join(); }

	/** Receive thread places incoming packet in a queue.
	 *  This method is run by a separate thread. It simply receives
	 *  packets from the datagram socket and places them in a queue.
	 *  If the queue is full when a packet arrives, it is discarded.
	 */
	public void run() {
		double sec = 1000000000;
		double t0 = System.nanoTime()/sec;
		double now, eventTime, firstEventTime;
		now = eventTime = firstEventTime = 0;

		Packet p; Pair<Packet,Integer> pp;
		byte[] buf = new byte[2000];
                DatagramPacket dg = new DatagramPacket(buf, buf.length);

		// run until nothing has happened for 5 seconds
		while (eventTime == 0 || now < eventTime + 5) {
			now = System.nanoTime()/sec - t0;
	                try {
	                        sock.receive(dg);
	                } catch(SocketTimeoutException e) {
	                        continue; // check for termination, then retry
	                } catch(Exception e) {
	                        System.err.println("Receiver: receive "
						    + "exception: " + e);
	                        System.exit(1);
			}
			p = new Packet();
			if (!p.unpack(dg.getData(), dg.getLength())) {
                        	System.err.println("Receiver: error while "
						   + "unpacking packet");
                       		System.exit(1);
                	}
			// keep going while there are app packets
			if (p.protocol == 1) eventTime = now;
//			if (p.protocol == 1) System.out.println("Receiver:still p.protocol = 1");
			pp = null;
			int i = 0;
			for (NborInfo nbor : nborList) {
				if (dg.getAddress().equals(nbor.hostIp)) {
					pp = new Pair<Packet,Integer>(p,i);
					break;
				}
				i++;
			}
			if (pp == null) {
				System.err.println("Receiver: arriving packet "
					+ "came from a non-neighbor");
				System.err.flush();
				System.exit(1);
			}
			if (debug >= 4 || 
			    (debug == 3 && p.protocol == 2) ||
			    (debug == 2 && p.protocol == 2 &&
			     p.payload.indexOf("advert") >= 0) ||
			    (debug == 2 && p.protocol == 2 &&
			     p.payload.indexOf("fadvert") >= 0)) {
                        	System.out.printf(
                                    "%s received from %s at %.3f\n%s\n",
				    "" + sock.getLocalSocketAddress(),
                                    "" + dg.getSocketAddress(),
				    now, "" + p);
                       		System.out.flush();
			}
			rcvq.offer(pp);		// discard if rcvq full
			if (firstEventTime == 0) firstEventTime = now;
		}
	}

	/** Receive a packet.
	 *  @return the next packet that has been received on the socket;
	 *  will block if no packets available
	 */
	public Pair<Packet,Integer> receive() {
		Pair<Packet,Integer> pp = null;
		try {
			pp = rcvq.take();
		} catch(Exception e) {
			System.err.println("Receiver:receive: exception " + e);
			System.exit(1);
		}
		return pp;
	}

	/** Test if there is an incoming packet.
	 *  @return true if there is a packet that available, else false
	 */
	public boolean incoming() { return rcvq.size() > 0; }
}
