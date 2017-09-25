package org.sensorhub.impl.sensor.nldn;

public class NldnRecord
{
	float [] lat;
	float [] lon;
	long timeUtc;
	String tstr; //?/
	double alt;
	float [][] nldn;
	
	public float [] getLat() {
		return lat;
	}
	public void setLat(float [] lat) {
		this.lat = lat;
	}
	public float[] getLon() {
		return lon;
	}
	public void setLon(float[] lon) {
		this.lon = lon;
	}
	public long getTimeUtc() {
		return timeUtc;
	}
	public void setTimeUtc(long timeUtc) {
		this.timeUtc = timeUtc;
	}
	public String getTstr() {
		return tstr;
	}
	public void setTstr(String tstr) {
		this.tstr = tstr;
	}
	public double getAlt() {
		return alt;
	}
	public void setAlt(double alt) {
		this.alt = alt;
	}
	public float[][] getMesh() {
		return nldn;
	}
	public void setMesh(float[][] mesh) {
		this.nldn = mesh;
	}
}
