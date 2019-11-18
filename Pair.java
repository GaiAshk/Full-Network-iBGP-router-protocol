/** Simple generic pair class with mutable elements. */
public class Pair<L,R> {
	public L left;		// note: public members
	public R right;

	/** Construct a pair with specified left/right elements. */
	public Pair(L left, R right) {
		this.left = left; this.right = right;
	}

	@Override
	public int hashCode() { return left.hashCode() ^ right.hashCode(); }

	@Override
	public boolean equals(Object o) {
		if (o == null) return false;
		if (!(o instanceof Pair)) return false;
		Pair pairo = (Pair) o;
		return	this.left.equals(pairo.left) &&
			this.right.equals(pairo.right);
	}

	@Override
	public String toString() {
		return "(" + left + "," + right + ")";
	}
}
