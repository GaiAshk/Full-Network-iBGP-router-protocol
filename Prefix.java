/** This class represents an IP address prefix. */
public class Prefix {
	public int adr;		// address part of prefix
	public int leng;	// length of the prefix
	public int mask;	// mask for prefix

	/** Construct a prefix with specified adr and mask.
	 *  @param adr is an integer representation of an IP address
	 *  @param leng is the length of the prefix in [0,32]
	 */
	public Prefix(int adr, int leng) {
		this.adr = adr; 
		this.leng = Math.min(32,leng);
		this.leng = Math.max(0,this.leng);
		this.mask = (this.leng == 0 ? 0 : 
			     (this.leng == 32 ? 0xffffffff :
			      (~((1 << (32-this.leng)) - 1))));
		adr &= mask;
	}

	/** Construct a prefix from a String.
	 *  @param pfxString is a String representing an ip address prefix;
	 *  for example, 1.2.3.4/20
	 */
	public Prefix(String pfxString) {
		String[] chunks = pfxString.split("/");
		adr = Util.string2ip(chunks[0].trim());
		leng = Integer.parseInt(chunks[1].trim());
		leng = Math.min(32,leng); leng = Math.max(0,leng);
		mask = (leng == 0 ? 0 : 
			     (leng == 32 ? 0xffffffff :
			      (~((1 << (32-leng)) - 1))));
		adr &= mask;
	}

	/** Return true if the given object has the same value as this Prefix
	 *  @param o is a Prefix object
	 *  @return true if o has the same value as this object
	 */
	public boolean equals(Object o) {
		if (o == null) return false;
		if (!(o instanceof Prefix)) return false;
		Prefix pairo = (Prefix) o;
		return adr == pairo.adr && leng == pairo.leng;
	}

	/** Test if an address matches the prefix.
	 *  @param adr is an address to be tested
	 *  @return true if the address matches this prefix
	 */
	public boolean matches(int adr) {
		return ((adr & mask) == this.adr);
	}

	public String toString() {
		return Util.ip2string(adr) + "/" + leng;
	}
}
