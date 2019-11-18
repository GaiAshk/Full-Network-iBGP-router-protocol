/** Source/Sink class
 *  This class generates packet payloads of random length
 *  adds a timestamp and sends them to random destinations.
 *  It also receives payloads sent by others and echos them back.
 *
 *  When a new payload is generated, it is passed to a Forwarder object,
 *  using its send method. Similarly, packets are received from the
 *  Forwarder object using its receive method.
 *
 *  The thread is started using the start method (which calls the
 *  run method in a new thread of control). It can be stopped
 *  using the stop method; this causes the run method to terminate
 *  its main loop and print a short status report, then return.
 */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class SrcSnk implements Runnable {
	private Thread myThread;	// thread that executes run() method

	private double delta;		// time between packets in ns
	private double runLength;		// amount of time to run in ns
	private Forwarder fwdr;		// reference to Forwarder object

	private ArrayList<String> destList; // list of destinations

	private static final double sec = 1000000000;  // ns per sec

	private boolean quit;		// stop thread when true

	/** Initialize a new SrcSnk object
	 *  @param delta is the amount of time to wait
	 *  between sending packets (in mis)
	 *  @param runLength is the length of the time
	 *  interval during which packets should be sent (in seconds)
	 *  @param fwdr is a reference to a Forwarder object
	 */
	SrcSnk(double delta, double runLength, Forwarder fwdr,
			ArrayList<String> destList) {
		this.delta = delta; this.runLength = runLength;
		this.fwdr = fwdr; this.destList = destList;
		this.quit = false;
	}

	/** Instantiate and start a thread to execute run(). */
	public void start() {
		myThread = new Thread(this); myThread.start();
	}

	/** Terminate the thread. */
	public void stop() throws Exception { quit = true; myThread.join(); }

	private class Stats {
		public int count;	public double totalDelay;
		public double minDelay;	public double maxDelay;

		Stats(int count, double totalDelay,
		      double minDelay, double maxDelay) {
			this.count = count; this.totalDelay = totalDelay;
			this.minDelay = minDelay; this.maxDelay = maxDelay;
		}
	}

	/** Run the SrcSnk thread.
	 *  This method executes a loop that generates new outgoing
	 *  payloads and receives incoming payloads. It sends packets
	 *  for a specified period of time and terminates after the stop
	 *  method is called.
	 */
	public void run() {
		double t0 = System.nanoTime()/sec;
		double now = 0;
		double next = 1;
		double stopTime = next + runLength;

		String payloadString = "supercalifragisticexpialidocious";
		payloadString += payloadString; payloadString += payloadString;
		payloadString += payloadString; payloadString += payloadString;
		int payLen = payloadString.length();
		int destCnt = destList.size();

		HashMap<String,Stats> delayStats=new HashMap<String,Stats>();
		for (String dest : destList) {
			delayStats.put(dest,new Stats(0,0,10,0));
		}

		Pair<String,String> sp;
		Random rand = new Random();
		while (!quit) {
			now = System.nanoTime()/sec - t0;
			if (fwdr.incoming()) {
				sp = fwdr.receive();
				String[] chunks = sp.left.split(":",2);
				if (chunks[0].trim().equals("ping")) {
					sp.left = "ping reply:" + chunks[1];
					fwdr.send(sp.left, sp.right);
				} else {
					chunks = chunks[1].split("\n",2);
					double d = Double.parseDouble(
							chunks[0].trim());
					d = (now - d)/2;
					Stats s = delayStats.get(sp.right);
					s.count++;
					s.totalDelay += d;
					s.minDelay = Math.min(s.minDelay,d);
					s.maxDelay = Math.max(s.maxDelay,d);
				}
			} else if (now > next && now < stopTime &&
			     	   fwdr.ready() && delta > 0) {
				// send an outgoing payload
				int i = rand.nextInt(payLen);
				int j = (i+1) + (rand.nextInt(payLen-i));
				sp = new Pair<String,String>("","");
				sp.left = "ping: " + now + "\n"
					  + payloadString.substring(i,j) + "\n";
				sp.right = destList.get(rand.nextInt(destCnt));
				fwdr.send(sp.left, sp.right);
				next += delta;
			} else {
				try {
					Thread.sleep(1);
				} catch(Exception e) {
					System.err.println("SrcSnk:run: "
						+ "can't sleep " + e);
					System.exit(1);
				}
			}
		}
		String ss = String.format("SrcSnk statistics\n" +
				   "%8s %8s %8s %8s %8s\n", "destIp", "count","avgDelay","minDelay","maxDelay");
		for (String ip : delayStats.keySet()) {
			Stats s = delayStats.get(ip);
			if (s.count > 0) {
				ss += String.format("%8s %8d %8.3f %8.3f %8.3f\n",
					ip.toString(), s.count,
					s.totalDelay/s.count,
					s.minDelay, s.maxDelay);
			}
		}
		System.out.println(ss);
	}
}

