package org.deftserver.util;

/** A simple immutable 2-tuple. */
public class Pair<T1, T2> {

	public final T1 _1;
	public final T2 _2;

	/** Creates the pair {@code (first, second)}. */
	public Pair(T1 first, T2 second) {
		this._1 = first;
		this._2 = second;
	}

	/** Factory for a pair {@code (_1, _2)} with inferred type arguments. */
	public static <X, Y> Pair<X, Y> of(X _1, Y _2) {
		return new Pair<X, Y>(_1, _2);
	}
}
