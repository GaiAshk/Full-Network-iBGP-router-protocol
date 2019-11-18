/** Utility class with static methods for addresses and prefixes */
public class Util {
	/** Convert an integer IP address to a string.
	 *
	 *  @param ip is an integer that represents an IP address
	 *  @return the String representing the value of ip in dotted decimal
	 *  notation
	 */
	public static String ip2string(int ip) {
		return  "" + ((ip >> 24) & 0xff) + "." + ((ip >> 16) & 0xff) +
			"." + ((ip >>  8) & 0xff) + "." + (ip & 0xff);
	}
	
	/** Convert a dotted decimal IP address string to an integer IP address.
	 *
	 *  @param ipString is a dotted decimal string
	 *  @return the corresponding integer IP address
	 */
	public static int string2ip(String ipString) {
		String[] parts = ipString.split("\\.");
		if (parts.length != 4) return 0;
		return	((Integer.parseInt(parts[0]) & 0xff) << 24) |
			((Integer.parseInt(parts[1]) & 0xff) << 16) |
			((Integer.parseInt(parts[2]) & 0xff) <<  8) |
			 (Integer.parseInt(parts[3]) & 0xff);
	}
}
