import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Router module for an overlay router.
 * <p>
 * Your documentation here
 * Should describe the protocol used by the router and a high level
 * description of when various packets are sent and how received
 * packets are processed
 */
public class Router implements Runnable {
  private Thread myThread;        // thread that executes run() method
  private int myIp;            // ip address in the overlay
  private String myIpString;        // String representation
  private ArrayList<Prefix> pfxList;    // list of prefixes to advertise
  private ArrayList<NborInfo> nborList;    // list of info about neighbors

  private class LinkInfo {        // class used to record link information
    public int peerIp;        // IP address of peer in overlay net
    public double cost;        // in seconds
    public boolean gotReply;    // flag to detect hello replies
    public int helloState;        // set to 3 when hello reply received
    // decremented whenever hello reply
    // is not received; when 0, link is down

    // link cost statistics
    public int count;
    public double totalCost;
    public double minCost;
    public double maxCost;

    LinkInfo() {
      cost = 0;
      gotReply = true;
      helloState = 3;
      count = 0;
      totalCost = 0;
      minCost = 10;
      maxCost = 0;
    }
  }

  private ArrayList<LinkInfo> lnkVec;     // indexed by link number

  private class Route {                // routing table entry
    public Prefix pfx;            // destination prefix for route
    public double timestamp;        // time this route was generated
    public double cost;            // cost of route in ns
    public LinkedList<Integer> path;// list of router IPs;
    // destination at end of list
    public int outLink;        // outgoing link for this route
    public boolean valid;        //indicate the valid of the route
  }

  private ArrayList<Route> rteTbl;        // routing table

  private Forwarder fwdr;                // reference to Forwarder object

  private double now;                                // current time in ns
  private static final double sec = 1000000000;    // ns per sec

  private int debug;        // controls debugging output
  private boolean quit;        // stop thread when true
  private boolean enFA;        // link failure advertisement enable


  /**
   * Initialize a new Router object.
   *
   * @param myIp     is an integer representing the overlay IP address of
   *                 this node in the overlay network
   * @param fwdr     is a reference to the Forwarder object through which
   *                 the Router sends and receives packets
   * @param pfxList  is a list of prefixes advertised by this router
   * @param nborList is a list of neighbors of this node
   * @param debug    is an integer that controls the amount of debugging
   *                 information that is to be printed
   */

  Router(int myIp, Forwarder fwdr, ArrayList<Prefix> pfxList,
         ArrayList<NborInfo> nborList, int debug, boolean enFA) {
    this.myIp = myIp;
    this.myIpString = Util.ip2string(myIp);
    this.fwdr = fwdr;
    this.pfxList = pfxList;
    this.nborList = nborList;
    this.debug = debug;
    this.enFA = enFA;

    lnkVec = new ArrayList<LinkInfo>();
    for (NborInfo nbor : nborList) {
      LinkInfo lnk = new LinkInfo();
      lnk.peerIp = nbor.ip;
      lnk.cost = nbor.delay;
      lnkVec.add(lnk);
    }
    rteTbl = new ArrayList<Route>();
    quit = false;
  }

  /**
   * Instantiate and start a thread to execute run().
   */
  public void start() {
    myThread = new Thread(this);
    myThread.start();
  }

  /**
   * Terminate the thread.
   */
  public void stop() throws Exception {
    quit = true;
    myThread.join();
  }

  /**
   * This is the main thread for the Router object.
   * <p>
   * handle 3 clocks now - time since start, helloTime - time since last hello,
   * and pvSendTime - time since last advertisment was initialized
   *
   * if hello wasn't sent for 1 sec send hellos
   * else if advertizment wan't inizialized for 10sec send advertisments
   * else if incoming packt from fwdr - handle incoming (method)
   * else take a nap for 1ms
   */
  public void run() {
    double t0 = System.nanoTime() / sec;
    double helloTime, pvSendTime;
    now = (System.nanoTime() / sec) - t0;
    helloTime = pvSendTime = now;
    while (!quit) {
      now = (System.nanoTime() / sec) - t0;
      // if it's time to send hello packets, do it
      if (now - helloTime > 1) {
        helloTime = now;
        this.sendHellos();
      }
      // else if it's time to send advertisements, do it
      else if (now - pvSendTime > 10) {
        pvSendTime = now;
        this.sendAdverts();

      }
      // else if the forwarder has an incoming packet
      // to be processed, retrieve it and process it
      else if (fwdr.incomingPkt()) {
        handleIncoming();
      }
      // else nothing to do, so take a nap
      else {
        try {
          myThread.sleep(1);
        } catch (Exception e) {
          System.err.println("Forwarder:send: put exception" + e);
          System.exit(1);
        }
      }
    }
    String s = String.format("Router link cost statistics\n" +
            "%8s %8s %8s %8s %8s\n", "peerIp", "count", "avgCost",
        "minCost", "maxCost");
    for (LinkInfo lnk : lnkVec) {
      if (lnk.count == 0) continue;
      s += String.format("%8s %8d %8.3f %8.3f %8.3f\n",
          Util.ip2string(lnk.peerIp), lnk.count,
          lnk.totalCost / lnk.count,
          lnk.minCost, lnk.maxCost);
    }
    System.out.println(s);
  }

  /**
   * Lookup route in routing table.
   *
   * @param pfx is IP address prefix to be looked up.
   * @return a reference to the Route that matches the prefix or null
   */
  private Route lookupRoute(Prefix inputPfx) {
    for (int i = 0; i < rteTbl.size(); i++) {
      if (rteTbl.get(i).pfx.equals(inputPfx)) {
        return rteTbl.get(i);
      }
    }
    return null;
  }

  /**
   * Add a route to the routing table.
   *
   * @param rte is a route to be added to the table; no check is
   *     done to make sure this route does not conflict with an existing route
   */
  private void addRoute(Route rte) {
    rteTbl.add(rte);
  }

  /**
   * Update a route in the routing table.
   *
   * @param rte   is a reference to a route in the routing table.
   * @param nuRte is a reference to a new route that has the same prefix as rte
   * @return true if rte is modified, else false
   * <p>
   * This method replaces certain fields in rte with fields
   * in nuRte. Specifically,
   * <p>
   * if nuRte has a link field that refers to a disabled
   * link, ignore it and return false
   * <p>
   * else, if the route is invalid, then update the route
   * and return true,
   * <p>
   * else, if both routes have the same path and link,
   * then the timestamp and cost fields of rte are updated
   * <p>
   * else, if nuRte has a cost that is less than .9 times the
   * cost of rte, then all fields in rte except the prefix fields
   * are replaced with the corresponding fields in nuRte
   * <p>
   * else, if nuRte is at least 20 seconds newer than rte
   * (as indicated by their timestamps), then all fields of
   * rte except the prefix fields are replaced
   * <p>
   * else, if the link field for rte refers to a link that is
   * currently disabled, replace all fields in rte but the
   * prefix fields
   */
  private boolean updateRoute(Route rte, Route nuRte) {
    // nuRte link is disabled
    boolean flag = false;
    if (!nuRte.valid) {
      flag = false;
      // rte is invalid, replace rte with nuRte
    } else if (!rte.valid) {
      flag = true;
      //rte path and link are the same as in nuRte, update cost and timestamp
    } else if (rte.path.equals(nuRte.path) && (rte.outLink == nuRte.outLink)) {
      flag = true;
    } else if (nuRte.cost <= (0.9 * rte.cost)) {
      flag = true;
    } else if (nuRte.timestamp >= (rte.timestamp + 20)) {
      flag = true;
    }
    if (flag) {
      rte.path = nuRte.path;
      rte.cost = nuRte.cost;
      rte.outLink = nuRte.outLink;
      rte.pfx = nuRte.pfx;
      rte.timestamp = nuRte.timestamp;
      rte.valid = nuRte.valid;
    }
    return flag;
  }

  /**
   * Send hello packet to all neighbors.
   * <p>
   * First check for replies. If no reply received on some link,
   * update the link status by subtracting 1. If that makes it 0,
   * the link is considered down, so we mark all routes using
   * that link as invalid. Also, if certain routes are marked as
   * invalid, we will need to print the table if debug larger
   * than 1, and we need to send failure advertisement by
   * calling sendFailureAdvert if failure advertisement is enable.
   */
  public void sendHellos() {
    for (LinkInfo lnkInfo : lnkVec) {
      int lnk = lnkVec.indexOf(lnkInfo);
      boolean routeHasChanged = false;
      // if no reply to the last hello, subtract 1 from
      // link status if it's not already 0
      if (!lnkInfo.gotReply) {
        if (lnkInfo.helloState == 1) {
          // go through the routes to check routes
          // that contain the failed link
          for (Route rte : rteTbl) {
            boolean prevVal = (rte.valid) ? true : false;
            rte.valid = (rte.outLink == lnk) ? false : prevVal;
            routeHasChanged = (rte.valid != prevVal) ? true : routeHasChanged;
          }
        }
        // substract hello state if it is more than 0
        lnkInfo.helloState = (lnkInfo.helloState == 0) ?
            0 : lnkInfo.helloState - 1;
      }
      // print routing table if debug is enabled
      // and valid field of route is changed
      if (routeHasChanged && debug > 0) {
        printTable();
      }
      // send link failure advertisement if enFA is enabled
      // and valid field of route is changed
      if (routeHasChanged && enFA) this.sendFailureAdvert(lnk);
      // send new hello, after setting gotReply to false
      lnkInfo.gotReply = false;
      while (!fwdr.ready4pkt()) { /* busy waiting */ }
      // make an hello packet p
      Packet p = new Packet();
      p.protocol = 2;
      p.ttl = 99;
      p.srcAdr = this.myIp;
      p.destAdr = lnkInfo.peerIp;
      p.payload = "RPv0\ntype: hello\ntimestamp: " + now + "\n";
      fwdr.sendPkt(p, lnk);
    }
  }

  /**
   * Send initial path vector to each of our neighbors.
   */
  public void sendAdverts() {
    // for each prefix, advertise it
    for (int i = 0; i < pfxList.size(); i++) {
      // send advertisement
      for (int j = 0; j < lnkVec.size(); j++) {
        // make an advert packet p
        Packet p = new Packet();
        p.protocol = 2;
        p.ttl = 99;
        p.srcAdr = this.myIp;
        //update destAdress
        p.destAdr = lnkVec.get(j).peerIp;
        p.payload = String.format("RPv0\ntype: advert\n"
                + "pathvec: %s %.3f 0 %s\n",
            this.pfxList.get(i), now, this.myIpString);
        //send packet in the right link
        int outLink = j;
        while (!fwdr.ready4pkt()) { /* busy waiting */ }
        fwdr.sendPkt(p, outLink);
      }
    }
  }

  /**
   * Send link failure advertisement to all available neighbors
   *
   * @param failedLnk is the number of link on which is failed.
   */
  public void sendFailureAdvert(int failedLnk) {
    int failIp = lnkVec.get(failedLnk).peerIp;
    String failIpString = Util.ip2string(failIp);

    for (int lnk = 0; lnk < nborList.size(); lnk++) {
      if (lnkVec.get(lnk).helloState == 0) continue;
      Packet p = new Packet();
      p.protocol = 2;
      p.ttl = 99;
      p.srcAdr = myIp;
      p.destAdr = lnkVec.get(lnk).peerIp;
      p.payload = String.format("RPv0\ntype: fadvert\n"
              + "linkfail: %s %s %.3f %s\n",
          myIpString, failIpString, now, myIpString);
      fwdr.sendPkt(p, lnk);
    }
  }

  /**
   * Retrieve and process packet received from Forwarder.
   * <p>
   * For hello packets, we simply echo them back.
   * For replies to our own hello packets, we update costs.
   * For advertisements, we update routing state and propagate
   * as appropriate.
   */
  public void handleIncoming() {
    // parse the packet payload
    Pair<Packet, Integer> pp = fwdr.receivePkt();
    Packet p = pp.left;
    int lnk = pp.right;

    String[] lines = p.payload.split("\n");
    if (!lines[0].equals("RPv0")) return;

    String[] chunks = lines[1].split(":");
    if (!chunks[0].equals("type")) return;
    String type = chunks[1].trim();

    // if it's an route advert, call handleAdvert
    if (type.equals("advert")) {
      handleAdvert(lines, lnk);
    }
    // if it's a link failure advert, call handleFailureAdvert
    else if (type.equals("fadvert")) {
      handleFailureAdvert(lines, lnk);
    }
    // if it's a hello, echo it back
    else if (type.equals("hello")) {
      //make a hello2u packet
      int destAdress = p.srcAdr;
      byte newProtocol = p.protocol;
      String newPayload = lines[0] + "\ntype: hello2u\n" + lines[2] + "\n";
      Packet newP = new Packet();
      newP.srcAdr = myIp;
      newP.destAdr = destAdress;
      newP.ttl = 99;
      newP.protocol = newProtocol;
      newP.payload = newPayload;
      while (!fwdr.ready4pkt()) { /* busy waiting */ }
      //send the hello2u packet
      fwdr.sendPkt(newP, lnk);
    }
    // else it's a reply to a hello packet
    else if (type.equals("hello2u")) {
      // use timestamp to determine round-trip delay
      double incomingTimestamp =
          Double.parseDouble(lines[2].split(":")[1].trim());
      double rtt = now - incomingTimestamp;
      double linkCost = rtt / 2;
      // use this to update the link cost using exponential
      // weighted moving average method
      LinkInfo lnkInfo = lnkVec.get(lnk);
      double alpha = 0.1;
      lnkInfo.cost = alpha * linkCost + (1 - alpha) * lnkInfo.cost;
      // also, update link cost statistics
      lnkInfo.count++;
      lnkInfo.totalCost += lnkInfo.cost;
      lnkInfo.maxCost = (lnkInfo.cost > lnkInfo.maxCost) ?
          lnkInfo.cost : lnkInfo.maxCost;
      lnkInfo.minCost = (lnkInfo.cost < lnkInfo.minCost) ?
          lnkInfo.cost : lnkInfo.minCost;
      // also, set gotReply to true
      lnkInfo.gotReply = true;
      lnkInfo.helloState = 3;
    }
  }

  /**
   * Handle an advertisement received from another router.
   *
   * @param lines is a list of lines that defines the packet;
   *              the first two lines have already been processed at this point
   * @param lnk   is the number of link on which the packet was received
   */
  private void handleAdvert(String[] lines, int lnk) {
    // example path vector line
    // pathvec: 1.2.0.0/16 345.678 .052 1.2.0.1 1.2.3.4
    // Parse the path vector line.

    // if the advert came from an invalid link disrigard this advert
    if (lnkVec.get(lnk).helloState == 0) {
      return;
    }

    // process the lines array
    String[] pathvec = lines[2].split(" ");
    Prefix pfx = new Prefix(pathvec[1]);
    double inTs = Double.parseDouble(pathvec[2]);
    double inCost = Double.parseDouble(pathvec[3]);
    LinkedList<Integer> newPath = new LinkedList<>();

    // If there is loop in path vector, ignore this packet.
    // in the same process build a new path
    int receivedAdvertFrom = -1;
    for (int i = 4; i < pathvec.length; i++) {
      newPath.add(Util.string2ip(pathvec[i].trim()));
      if (i == 4) {
        receivedAdvertFrom = Util.string2ip(pathvec[i].trim());
      }
      if (myIpString.equals(pathvec[i].trim())) {
        return;
      }
    }
    // add myIP to the head of the newPath Lnked List
    newPath.addFirst(this.myIp);

    // Form a new route, with cost equal to path vector cost
    // plus the cost of the link on which it arrived.
    Route newRte = new Route();
    newRte.pfx = pfx;
    newRte.timestamp = now;
    newRte.path = newPath;
    // add cost field of router
    double newPathCost = inCost + lnkVec.get(lnk).cost;
    newRte.cost = newPathCost;
    newRte.outLink = lnk;
    newRte.valid = true;

    // Look for a matching route in the routing table and update as appropriate;
    // whenever an update changes the path, print the table if debug>0;
    Route rteInTbl = this.lookupRoute(pfx);

    boolean rteTblHasChanged = false;
    boolean addToFwrd = false;

    LinkedList<Integer> pathBeforeUpdate = (this.lookupRoute(pfx) == null) ?
        null : this.lookupRoute(pfx).path;
    int outlinkBeforeUpdate = (this.lookupRoute(pfx) == null) ?
        (-1) : this.lookupRoute(pfx).outLink;

    if (rteInTbl == null) {
      this.addRoute(newRte);
      addToFwrd = true;
      rteTblHasChanged = true;
    } else {
      rteTblHasChanged = this.updateRoute(rteInTbl, newRte);
    }
    // whenever an update changes the output link,
    // update the forwarding table as well.
    LinkedList<Integer> pathAfterUpdate = this.lookupRoute(pfx).path;
    int outlinkAfterUpdate = this.lookupRoute(pfx).outLink;
    boolean outLinkHasChanged = outlinkBeforeUpdate != outlinkAfterUpdate;
    boolean pathHasChanged = (pathBeforeUpdate == null) ?
        true : (!pathBeforeUpdate.equals(pathAfterUpdate));

    if (debug > 0 && pathHasChanged) {
      this.printTable();
    }

    if (outLinkHasChanged || addToFwrd) fwdr.addRoute(pfx, lnk);
    // If the new route changed the routing table,
    // extend the path vector and send it to other neighbors.
    if (rteTblHasChanged || pathHasChanged || outLinkHasChanged) {
      // send advertisement
      for (int i = 0; i < lnkVec.size(); i++) {
        int tempLnk = i;

        // make an advert packet p
        Packet p = new Packet();
        p.protocol = 2;
        p.ttl = 99;
        p.srcAdr = this.myIp;
        //change destAdr
        p.destAdr = lnkVec.get(i).peerIp;
        p.payload = String.format("RPv0\ntype: advert\n"
                + "pathvec: %s %.3f %.3f",
            pfx, now, newPathCost);
        //format the ip path of this packet
        for (int ip : newPath) {
          p.payload += " " + Util.ip2string(ip);
        }
        p.payload += "\n";
        // dont send advert to the router that sent you
        if (receivedAdvertFrom == lnkVec.get(i).peerIp) {
          continue;
        }
        //send packet on the right link
        while (!fwdr.ready4pkt()) { /* busy waiting */ }
        fwdr.sendPkt(p, tempLnk);
      }
    }
  }

  /**
   * Handle the failure advertisement received from another router.
   *
   * @param lines is a list of lines that defines the packet;
   *              the first two lines have already been processed at this point
   * @param lnk   is the number of link on which the packet was received
   */
  private void handleFailureAdvert(String[] lines, int lnk) {
    // example path vector line
    // fadvert: 1.2.0.1 1.3.0.1 345.678 1.4.0.1 1.2.0.1
    // meaning link 1.2.0.1 to 1.3.0.1 is failed
    // Parse the path vector line.
    String[] pathvec = lines[2].split(" ");
    int startLinkFailure = Util.string2ip(pathvec[1]);
    int endLinkFailure = Util.string2ip(pathvec[2]);
    String startLinkFailureString = pathvec[1];
    String endLinkFailureString = pathvec[2];
    double inTs = Double.parseDouble(pathvec[3]);
    LinkedList<Integer> path = new LinkedList<>();
    // If there is loop in path vector, ignore this packet.
    int receivedFAdvertFrom = -1;
    for (int i = 4; i < pathvec.length; i++) {
      path.add(Util.string2ip(pathvec[i]));
      if (i == 4) {
        receivedFAdvertFrom = Util.string2ip(pathvec[i].trim());
      }
      if (myIpString.equals(pathvec[i].trim())) {
        return;
      }
    }
    // go through routes to check if it contains the link
    // set the route as invalid (false) if it does
    boolean atLeastOneRouteUpdated = false;
    // loop on all routes in rte table
    for (int i = 0; i < rteTbl.size(); i++) {
      // loop on the path of this entery in the rte table
      for (int j = 0; j < rteTbl.get(i).path.size() - 1; j++) {
        //if the entery contains the path that faild make this path invalid
        if (rteTbl.get(i).path.get(j) ==
            startLinkFailure && rteTbl.get(i).path.get(j + 1) == endLinkFailure) {
          // if the path was valid and now it is going to change to not valid
          if (rteTbl.get(i).valid) {
            atLeastOneRouteUpdated = true;
          }
          rteTbl.get(i).valid = false;
        }
      }
    }
    // update the time stamp if route is changed
    if (atLeastOneRouteUpdated) {
      inTs = now;
    }
    // print route table if route is changed and debug is enabled
    if (atLeastOneRouteUpdated && debug > 0) {
      printTable();
    }
    //If one route is changed, extend the message and send it to other neighbors.
    if (atLeastOneRouteUpdated) {
      // add own IP address to front of path
      path.addFirst(this.myIp);
      // send failed advertisement packet
      for (LinkInfo lnkInfo : lnkVec) {

        // make an fadvert packet p
        Packet p = new Packet();
        p.protocol = 2;
        p.ttl = 99;
        p.srcAdr = this.myIp;
        p.destAdr = lnkInfo.peerIp;
        p.payload = String.format("RPv0\ntype: fadvert\n"
                + "linkfail: %s %s %.3f",
            startLinkFailureString, endLinkFailureString, inTs);
        //add ips to the path vector
        for (int ip : path) {
          p.payload += " " + Util.ip2string(ip);
        }
        p.payload += "\n";
        int tempLnk = lnkVec.indexOf(lnkInfo);
        // don't send to router that sent the advert to me
        if (receivedFAdvertFrom == lnkInfo.peerIp) {
          continue;
        }
        //send packet of the right link
        while (!fwdr.ready4pkt()) { /* busy waiting */ }
        fwdr.sendPkt(p, tempLnk);
      }
    }
  }

  /**
   * Print the contents of the routing table.
   */
  public void printTable() {
    String s = String.format("Routing table (%.3f)\n"
            + "%10s %10s %8s %5s %10s \t path\n", now, "prefix",
        "timestamp", "cost", "link", "VLD/INVLD");
    for (Route rte : rteTbl) {
      s += String.format("%10s %10.3f %8.3f",
          rte.pfx.toString(), rte.timestamp, rte.cost);

      s += String.format(" %5d", rte.outLink);

      if (rte.valid == true)
        s += String.format(" %10s", "valid");
      else
        s += String.format(" %10s \t", "invalid");

      for (int r : rte.path)
        s += String.format(" %s", Util.ip2string(r));

      if (lnkVec.get(rte.outLink).helloState == 0)
        s += String.format("\t ** disabled link");
      s += "\n";
    }
    System.out.println(s);
  }
}