/** Datagram sender.
 *
 *  This class implements the sending side of a Substrate object.
 *  It runs as a separate thread.
 */  

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Sender implements Runnable {
	private DatagramSocket sock;		// local socket
	private ArrayList<NborInfo> nborList;	// list of neighbors
	private boolean staticDelay;		// controls delay variability
	private int debug;			// controls debugging output

	private ArrayList<ArrayBlockingQueue<Pair<Packet,Double>>> qvec;
						// array of link queues
	private Thread myThread;	// thread that executes run() method
	private double now;		// current time in ns
	private final double sec = 1000000000; // ns per second

	Sender(DatagramSocket sock, ArrayList<NborInfo> nborList,
			boolean staticDelay, int debug) {
		this.sock = sock; this.nborList = nborList;
		this.staticDelay = staticDelay; this.debug = debug;

		// initialize array of link queues for received packets
		// each link queue stores a packet and its "arrival time"
		qvec = new ArrayList<ArrayBlockingQueue<Pair<Packet,Double>>>();
		for (int i = 0; i < nborList.size(); i++)
			qvec.add(new ArrayBlockingQueue<Pair<Packet,Double>>(
								1000,true));
	}

	/** Instantiate run() thread and start it running. */
	public void start() {
		myThread = new Thread(this); myThread.start();
	}

	/** Wait for thread to quit. */
	public void join() throws Exception { myThread.join(); }

	/** Send thread sends out-going packets to the network.
	 *  This method is run by a separate thread. Whenever there
	 *  is an outgoing packet to be sent, it sends it and waits
	 *  for the next.
	 */
	public void run() {
		double t0 = System.nanoTime()/sec;
		double eventTime, firstEventTime, adjustTime;
		now = eventTime = firstEventTime = 0;

		adjustTime = .1;
		double[] delay = new double[nborList.size()];
		for (int i = 0; i < nborList.size(); i++)
			delay[i] = nborList.get(i).delay;

		byte[] buf = new byte[1]; // dummy buffer, not really used 
                DatagramPacket dg = new DatagramPacket(buf,1);

		// run until nothing has happened for 3 seconds
		Pair<Packet,Double> pp; Packet p; double t;
		while (eventTime == 0 || now < eventTime + 3) {
			now = System.nanoTime()/sec - t0;
			boolean nothing2do = true;
			for (int lnk = 0; lnk < qvec.size(); lnk++) {
				if (qvec.get(lnk).size() == 0) continue;
				pp = qvec.get(lnk).peek();
				p = pp.left; t = pp.right;
				if (now < t + Math.abs(delay[lnk])) continue;

				try {
					qvec.get(lnk).take();
				} catch(Exception e) {
					System.err.println("Sender:run: " +
						"can't read from link queue");
					System.exit(1);
				}
				buf = p.pack();
               			if (buf == null) {
					System.err.println("Sender: "
						+ "packing error " + p);
					System.exit(1);
				}
				dg.setData(buf);
				dg.setLength(buf.length);
				dg.setAddress(nborList.get(lnk).hostIp);
				dg.setPort(31313);
				if (debug >= 4 || 
				    (debug == 3 && p.protocol == 2) ||
				    (debug == 2 && p.protocol == 2 &&
				     p.payload.indexOf("advert") >= 0) ||
				    (debug == 2 && p.protocol == 2 &&
				     p.payload.indexOf("fadvert") >= 0)) {
                        		System.out.printf(
                                	    "%s sending to %s at %.3f\n%s\n",
					    "" + sock.getLocalSocketAddress(),
                                	    "" + dg.getSocketAddress(),
					    now, "" + p);
                        		System.out.flush();
				}
	                	try {
					sock.send(dg);
				} catch(Exception e) {
					System.err.println("Sender: "

						+ "send error " + p);
					System.exit(1);
				}

				nothing2do = false;
				// keep going while there are app packets
				if (p.protocol == 1) eventTime = now;
//				if (p.protocol == 1) System.out.println("Sender:still p.protocol = 1");
				if (firstEventTime == 0) firstEventTime = now;
			}

			// adjust link delays every second
			if (!staticDelay && now > adjustTime) {
				for (int i = 0; i < delay.length; i++) {
					delay[i] += .002*(i+1)*(i+1)*(i+1);
					if (delay[i] > .5 ||
					    Math.random() < .02)
						delay[i] = -delay[i];
				}
				adjustTime += 1;
				nothing2do = false;
			}
			if (nothing2do) {
				try { Thread.sleep(1); } catch(Exception e) {
					System.out.println("Sender: "
							+ "can't sleep");
					System.exit(1);
				}
			}
		}
	}

	/** Send a packet on one of this router's links
	 *  @param p is packet to be sent
	 */
	public void send(Packet p, int link) {
		if (link < 0 || link >= qvec.size()) return;
		try {
			qvec.get(link).offer(new Pair<Packet,Double>(p,now));
		} catch(Exception e) {
			System.err.println("Sender:send sendq exception " + e);
			System.exit(1);
		}
	}

	/** Return true if ready to accept another packet. */
	public boolean ready(int link) {
		if (link < 0 || link >= qvec.size()) return false;
		return (qvec.get(link).remainingCapacity() > 0);
	}
}
