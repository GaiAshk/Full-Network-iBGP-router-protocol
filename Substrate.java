import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Substrate implements logical interfaces to neighboring overlay routers.
 *  For each neighbor, it delays packets by a specified amount to emulate
 *  link delays in an actual network.
 */
public class Substrate {
	private DatagramSocket sock;		// common socket
	private InetAddress myIp;		// IP bound to socket
	private ArrayList<NborInfo> nborList;	// list of neighbors
	private boolean staticDelay;		// controls delay variation
	private int debug;			// controls debugging output

	private Sender sndr;			// handles outgoing packets
	private Receiver rcvr;			// handles incoming packets
	
	/** Initialize a new Substrate object.
	 *  @param myIp is the IP address to bind to the socket
	 *  @param nborList is a list of NborInfo objects, one for each
	 *  neighbor of this overlay router
	 *  @param staticDelay is true if link delays are static; otherwise they
	 *  change over time
	 *  @param debug is an integer that controls the amount of debugging
 	 *  information to be printed out; 0 produces no debugging output,
	 *  larger values produce more
	 */
	Substrate(InetAddress myIp, ArrayList<NborInfo> nborList, 
		  boolean staticDelay, int debug) {
		// initialize instance variables
		this.myIp = myIp;
		this.nborList = nborList;
		this.staticDelay = staticDelay;
		this.debug = debug;

		// open and configure socket with timeout
		sock = null;
		try {
			sock = new DatagramSocket(31313,myIp);
			sock.setSoTimeout(100);
			sock.setReceiveBufferSize(1000000);
		} catch(Exception e) {
			System.out.println("Substrate: cannot create socket"
						+ e);
			System.exit(1);
		}

		// create sender and receiver
		sndr = new Sender(sock,nborList,staticDelay,debug);
		rcvr = new Receiver(sock,nborList,debug);
	}

	/** Start Substrate running. */
	public void start() { sndr.start(); rcvr.start(); }

	/** Wait for Substrate to stop. */
	public void join() throws Exception { sndr.join(); rcvr.join(); }

	/** Send a packet.
	 *  @param p is a packet to be sent
	 *  @param lnk is the number of the link on which it to be sent
	 */
	public void send(Packet p, int lnk) { sndr.send(p,lnk); }
		
	/** Test if substrate is ready to send more packets.
	 *  @param lnk is the number of a link
	 *  @return true if the link can accept more packets
	 */
	public boolean ready(int lnk) { return sndr.ready(lnk); }

	/** Retrieve the next packet from the substrate.
	 *  @return the next incoming packet and the number of the
	 *  link on which it arrived
	 */
	public Pair<Packet,Integer> receive() { return rcvr.receive(); }
	
	/** Test for the presence of incoming packets.
	 *  @return true if there are packets available to be received.
	 */
	public boolean incoming() { return rcvr.incoming(); }
}
