package com.chartplotter.route;
import java.util.Arrays;
final class LongIntMap {
	static final int MISS = Integer.MIN_VALUE;
	long[] k;
	int[] v;
	byte[] u;
	int n;
	int mask;
	LongIntMap(int size) {init(size);}
	int get(long key) {
		int i = hash(key) & mask;
		while (u[i] != 0) {
			if (k[i] == key) return v[i];
			i = i + 1 & mask;
		}
		return MISS;
	}
	void put(long key, int val) {
		if (n * 2 >= k.length) grow();
		int i = hash(key) & mask;
		while (u[i] != 0) {
			if (k[i] == key) {
				v[i] = val;
				return;
			}
			i = i + 1 & mask;
		}
		u[i] = 1;
		k[i] = key;
		v[i] = val;
		n++;
	}
	void clear() {
		Arrays.fill(u, (byte) 0);
		n = 0;
	}
	private void init(int size) {
		int c = 1;
		while (c < size) c <<= 1;
		k = new long[c];
		v = new int[c];
		u = new byte[c];
		mask = c - 1;
		n = 0;
	}
	private void grow() {
		long[] ok = k;
		int[] ov = v;
		byte[] ou = u;
		init(k.length << 1);
		for (int i = 0; i < ok.length; i++) {
			if (ou[i] != 0) put(ok[i], ov[i]);
		}
	}
	private static int hash(long x) {
		x += 0x9e3779b97f4a7c15L;
		x = (x ^ x >>> 30) * -4658895280553007687L;
		x = (x ^ x >>> 27) * -7723592293110705685L;
		return (int) (x ^ x >>> 31);
	}
}
