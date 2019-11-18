import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Forwarder for an overlay IP router.
 * <p>
 * This class implements a basic packet forwarder for a simplified
 * overlay IP router. It runs as a separate thread.
 * <p>
 * An application layer thread provides new packet payloads to be
 * sent using the provided send() method, and retrieves newly arrived
 * payloads with the receive() method. Each application layer payload
 * is sent as a separate packet, where each packet includes a protocol
 * field, a ttl, a source address and a destination address.
 */
public class Forwarder implements Runnable {
  private int myIp;    // this node's ip address in overlay
  private int debug;    // controls amount of debugging output
  private Substrate sub;    // Substrate object for packet IO
  private double now;    // current time in ns
  private final double sec = 1000000000; // # of ns in a second

  // forwarding table maps contains (prefix, link#) pairs
  private ArrayList<Pair<Prefix, Integer>> fwdTbl;

  // queues for communicating with SrcSnk
  private ArrayBlockingQueue<Packet> fromSrc;
  private ArrayBlockingQueue<Packet> toSnk;

  // queues for communicating with Router
  private ArrayBlockingQueue<Pair<Packet, Integer>> fromRtr;
  private ArrayBlockingQueue<Pair<Packet, Integer>> toRtr;

  private Thread myThread;
  private boolean quit;

  /**
   * Initialize a new Forwarder object.
   *
   * @param myIp  is this node's IP address in the overlay network,
   *              expressed as a raw integer.
   * @param sub   is a reference to the Substrate object that this object
   *              uses to handle the socket IO
   * @param debug controls the amount of debugging output
   */
  Forwarder(int myIp, Substrate sub, int debug) {
    this.myIp = myIp;
    this.sub = sub;
    this.debug = debug;

    // intialize forwarding table with a default route to link 0
    fwdTbl = new ArrayList<Pair<Prefix, Integer>>();
    fwdTbl.add(new Pair<Prefix, Integer>(new Prefix(0, 0), 0));

    // create queues for SrcSnk and Router
    fromSrc = new ArrayBlockingQueue<Packet>(1000, true);
    toSnk = new ArrayBlockingQueue<Packet>(1000, true);
    fromRtr = new
        ArrayBlockingQueue<Pair<Packet, Integer>>(1000, true);
    toRtr = new
        ArrayBlockingQueue<Pair<Packet, Integer>>(1000, true);
    quit = false;
  }

  /**
   * Start the Forwarder running.
   */
  public void start() throws Exception {
    myThread = new Thread(this);
    myThread.start();
  }

  /**
   * Terminate the Forwarder.
   */
  public void stop() throws Exception {
    quit = true;
    myThread.join();
  }

  /**
   * This is the main thread for the Forwarder object.
   * we can receive multiple types of packets from the Subtrate leyer this
   * thread can do the following
   * <p>
   * this method keeps track of time since the program started to run, called
   * now in seconds.
   * if there is an incoming message from sub:
   * if it is for myIp address pass to application if protocol is 1,
   * if protocol is 2 and to myIP pass to router
   * <p>
   * else if it is not for this IP address, find next hop from routing table
   * and pass to next hop
   * <p>
   * else if we have an incoming message from router pass it to the link the
   * router spesifies
   * <p>
   * else if we have an incoming message from application, pass to next hop by
   * a look up opartion
   * <p>
   * else take a 1ms nap
   * <p>
   * repeat until quit=true
   */
  public void run() {
    now = 0;
    double t0 = System.nanoTime() / sec;
    while (!quit) {
      now = System.nanoTime() / sec - t0;
      // if the Substrate has an incoming packet
      if (sub.incoming()) {
        // extract information from the subtrate
        Pair<Packet, Integer> inPair = sub.receive();
        Packet inPacket = inPair.left;
        int inLink = inPair.right;
        // if it's addressed to this overlay node
        if (inPacket.destAdr == myIp) {
          if (inPacket.protocol == 1) {
            // send to the SrcSnk
            toSnk.add(inPacket);
          } else {
            // send to the Router
            toRtr.add(inPair);
          }
        }
        // not to this overlay node: forward it
        else {
          int nextHopLink = this.lookup(inPacket.destAdr);
          inPacket.ttl--;
          if (inPacket.ttl == 0) {
            // do nothing; discard the packet
          } else if (nextHopLink == -1) {
            //next hop is not in our forwarding table
            // forward to defult router
          } else {
            // forwarder is not ready or full
            while (!this.ready()) { /* busy wating */ }
            // send packet to next hop
            sub.send(inPacket, nextHopLink);
          }
        }
      }
      // else if we have a packet from the Router to send
      else if (fromRtr.size() > 0) {
        // send it to the Substrate
        Pair<Packet, Integer> routerPair = null;
        try {
          routerPair = fromRtr.take();
        } catch (Exception e) {
          System.out.println("Forwarder:send: take exception" + e);
          System.exit(1);
        }
        // forwarder is not ready or full
        while (!this.ready()) { /* busy wating */ }
        // if forwarder is ready send to subtrate
        sub.send(routerPair.left, routerPair.right);
      }
      // else if we have a payload from the SrcSnk to send
      else if (fromSrc.size() > 0) {
        Packet outPkt = null;
        try {
          outPkt = fromSrc.take();
        } catch (Exception e) {
          System.out.println("Forwarder:send: take exception" + e);
          System.exit(1);
        }
        // lookup the outgoing link using dest IP address
        int destLink = this.lookup(outPkt.destAdr);
        // format a packet containing the payload and pass it to the Substrate
        sub.send(outPkt, destLink);
      } else {
        // else nothing to do, so take a nap
        try {
          myThread.sleep(1);
        } catch (Exception e) {
          System.err.println("Forwarder:send: put exception" + e);
          System.exit(1);
        }
      }
    }
  }

  /**
   * Add a route to the forwarding table.
   *
   * @param nuPrefix is a prefix to be added
   * @param nuLnk    is the number of the link on which to forward
   *                 packets matching the prefix
   *                 <p>
   *                 If the table already contains a route with the specified
   *                 prefix, the route is updated to use nuLnk. Otherwise,
   *                 a route is added.
   *                 <p>
   *                 If debug>0, print the forwarding table when done
   */
  public synchronized void addRoute(Prefix nuPrefix, int nuLnk) {
    // if table contains an entry with the same prefix, just update the link;
    boolean flag = false;
    Pair<Prefix, Integer> pp = new Pair<>(nuPrefix, nuLnk);
    for (int i = 0; i < fwdTbl.size(); i++) {
      if (nuPrefix.equals(fwdTbl.get(i).left)) {
        fwdTbl.set(i, pp);
        flag = true;
        break;
      } else if (fwdTbl.get(i).left.leng < nuPrefix.leng) { // optimization
        fwdTbl.add(i, pp);
        flag = true;
        break;
      }
    }
    // otherwise add an entry
    if (!flag) fwdTbl.add(pp);
    if (debug > 0) this.printTable();
  }

  /**
   * Print the contents of the forwarding table.
   */
  public synchronized void printTable() {
    String s = String.format("Forwarding table (%.3f)\n", now);
    for (Pair<Prefix, Integer> rte : fwdTbl)
      s += String.format("%s %s\n", rte.left, rte.right);
    System.out.println(s);
  }

  /**
   * Lookup route in fwding table.
   *
   * @param ip is an integer representing an IP address to lookup
   * @return nextHop link number or -1, if no matching entry.
   */
  private synchronized int lookup(int ip) {
    // assuming fwdTbl is sorted from longest prefix to shortest, first match
    // will be longest match
    for (int i = 0; i < fwdTbl.size(); i++) {
      if (fwdTbl.get(i).left.matches(ip)) {
        return fwdTbl.get(i).right;
      }
    }
    return -1;
  }

  /**
   * Send a message to another overlay host.
   *
   * @param message is a string to be sent to the peer
   */
  public void send(String payload, String destAdr) {
    Packet p = new Packet();
    p.srcAdr = myIp;
    p.destAdr = Util.string2ip(destAdr);
    p.protocol = 1;
    p.ttl = 99;
    p.payload = payload;
    try {
      fromSrc.put(p);
    } catch (Exception e) {
      System.err.println("Forwarder:send: put exception" + e);
      System.exit(1);
    }
  }

  /**
   * Test if Forwarder is ready to send a message.
   *
   * @return true if Forwarder is ready
   */
  public boolean ready() {
    return fromSrc.remainingCapacity() > 0;
  }

  /**
   * Get an incoming message.
   *
   * @return next message
   */
  public Pair<String, String> receive() {
    Packet p = null;
    try {
      p = toSnk.take();
    } catch (Exception e) {
      System.err.println("Forwarder:send: take exception" + e);
      System.exit(1);
    }
    return new Pair<String, String>(
        p.payload, Util.ip2string(p.srcAdr));
  }

  /**
   * Test for the presence of an incoming message.
   *
   * @return true if there is an incoming message
   */
  public boolean incoming() {
    return toSnk.size() > 0;
  }


  // the following methods are used by the Router


  /**
   * Send a message to another overlay Router.
   *
   * @param p   is a packet to be sent to another overlay node
   * @param lnk is the number of the link the packet should be forwarded on
   */
  public void sendPkt(Packet p, int lnk) {
    Pair<Packet, Integer> pp = new Pair<Packet, Integer>(p, lnk);
    try {
      fromRtr.put(pp);
    } catch (Exception e) {
      System.err.println("Forwarder:sendPkt: cannot write"
          + " to fromRtr " + e);
      System.exit(1);
    }
    // debug for print pkt
    if (debug > 2) printPkt(p, lnk, 0);
  }

  /**
   * Test if Forwarder is ready to send a packet from Router.
   *
   * @return true if Forwarder is ready
   */
  public boolean ready4pkt() {
    return fromRtr.remainingCapacity() > 0;
  }

  /**
   * Get an incoming packet.
   *
   * @return a Pair containing the next packet for the Router,
   * including the number of the link on which it arrived
   */
  public Pair<Packet, Integer> receivePkt() {
    Pair<Packet, Integer> pp = null;
    try {
      pp = toRtr.take();
    } catch (Exception e) {
      System.err.println("Forwarder:receivePkt: cannot read"
          + " from toRtr " + e);
      System.exit(1);
    }
    return pp;
  }

  /**
   * Test for the presence of an incoming packet for Router.
   *
   * @return true if there is an incoming packet
   */
  public boolean incomingPkt() {
    return toRtr.size() > 0;
  }

  public void printPkt(Packet p, int lnk, int inout) {
    // incoming pkt
    String s;
    if (inout == 1)
      s = String.format("Receive");
    else
      s = String.format("Send");
    s += String.format("Pkt from %s to %s through lnk %d\n",
        Util.ip2string(p.srcAdr), Util.ip2string(p.destAdr), lnk);
    s += String.format("%s\n", p.payload);
    System.out.println(s);
  }
}