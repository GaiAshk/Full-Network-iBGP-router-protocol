import java.net.*;

/** Objects in this class contain information about a neighbor of a router. */
public class NborInfo {
	public int ip;		// address of neighbor in the overlay net
	public InetAddress hostIp; // address of host on which neighbor runs
	public double delay;	// link delay parameter for neighbor

	/** Construct a prefix with specified adr and mask.
	 *  @param rawAdr is an integer representation of an IP address
	 *  @param leng is the length of the prefix in [0,32]
	 */
	public NborInfo(int ip, InetAddress hostIp, long delay) {
		this.ip = ip; this.hostIp = hostIp; this.delay = delay; 
	}

	/** Construct a prefix from a String.
	 *  @param nborString is a String representing neighbor information
	 *  for example, 1.2.3.4 192.168.4.3 20
	 */
	public NborInfo(String nborString) {
		String[] chunks = nborString.trim().split(" ");
		ip = Util.string2ip(chunks[0].trim());
		try {
			hostIp = InetAddress.getByName(chunks[1].trim());
		} catch(Exception e) {
			System.out.println("NborInfo: cannot construct object "
					   + "from string " + nborString);
			System.exit(1);
		}
		delay = Double.parseDouble(chunks[2].trim());
	}

	public String toString() {
		String s = hostIp.toString().substring(1);
		return Util.ip2string(ip) + " " + s + " " + delay;
	}
}
