/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2017 Botts Innovative Research, Inc. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.navDb;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.sensorhub.impl.sensor.navDb.NavDbEntry.Type;

public class LufthansaParser
{
	// DDMMSS.SS or DDDMMSS.ss
	// N30114030,W097401160
	private static Double parseCoordString(String s) throws NumberFormatException {
		char hemi = s.charAt(0);
		String dd, mm, ss;
		if(hemi=='N' || hemi=='S') {
			dd = s.substring(1,3);
			mm = s.substring(3,5);
			ss = s.substring(5,9);
		} else if(hemi=='E' || hemi=='W') {
			dd = s.substring(1,4);
			mm = s.substring(4,6);
			ss = s.substring(6,10);
		} else {
			throw new NumberFormatException("Cannot parse coord Str: " + s);
		}
		double deg = Double.parseDouble(dd);
		double min = Double.parseDouble(mm);
		double sec = Double.parseDouble(ss);
		sec = sec/100.;

		double coord = deg + (min/60.) + (sec/3600.);
		if(hemi == 'S' || hemi == 'W')
			coord = -1 * coord;
		return coord;
	}

	private static NavDbEntry parseAirport(String l) {
		String region = l.substring(1,4);
		String icao = l.substring(6, 10);
		String lats = l.substring(32,41);
		String lons = l.substring(41,51);
		String name = l.substring(93,123).trim();
		double lat;
		double lon;
		try {
			lat = parseCoordString(lats);
			lon = parseCoordString(lons);
		} catch (NumberFormatException e) {
			return null;
		}
		NavDbEntry airport = new NavDbEntry(Type.AIRPORT, icao, lat, lon);
		airport.name = name;
		airport.region = region;
		return airport;
	}

	private static NavDbEntry parseWaypoint(String l) {
		String region = l.substring(1,4);
		String icao = l.substring(13, 18);
		String lats = l.substring(32,41);
		String lons = l.substring(41,51);
		String name = l.substring(98,122).trim();
		double lat;
		double lon;
		try {
			lat = parseCoordString(lats);
			lon = parseCoordString(lons);
		} catch (NumberFormatException e) {
			return null;
		}
		NavDbEntry waypoint = new NavDbEntry(Type.WAYPOINT, icao, lat, lon);
		waypoint.name = name;
		waypoint.region = region;
		return waypoint;
	}

	private static NavDbEntry parseNavaid(String l) {
		String region = l.substring(1,4);
		String icao = l.substring(13, 17);
		String lats = l.substring(32,41);
		String lons = l.substring(41,51);
		String name = l.substring(93,123).trim();
		double lat;
		double lon;
		try {
			lat = parseCoordString(lats);
			lon = parseCoordString(lons);
		} catch (NumberFormatException e) {
			return null;
		}

		NavDbEntry navaid = new NavDbEntry(Type.NAVAID, icao, lat, lon);
		navaid.name = name;
		navaid.region = region;
		return navaid;
	}


	public static List<NavDbEntry> getNavDbEntries(Path dbPath) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(dbPath.toString()));
		List<NavDbEntry> entries = new ArrayList<>();
		int lineCnt = 1;
		boolean eof = false;
		int idStartIdx = -1, idStopIdx = -1;
		String prevId = "";
		char continuationNum = 0;
		while(true) {
			String line = br.readLine();
			if(line == null)
				break;
			//			if(!regions.isEmpty())  check region if desired;
			NavDbEntry entry = null;
//			System.err.println(line);

			switch(line.charAt(4)) {
			case 'P':
				//				continuationNum = line.charAt(21);
				//				if(!(continuationNum == '1'))
				//					continue;
				String id = line.substring(6, 10);
				if(prevId.equals(id))
					continue;
				entry = parseAirport(line);
//				if(entry == null) 
//					continue;
				prevId = entry.id;
				break;
			case 'D':
				continuationNum = line.charAt(21);
				if(!(continuationNum == '1'))
					continue;
				entry = parseNavaid(line);
				break;
			case 'E':
				if(line.charAt(5) == 'A') {
					continuationNum = line.charAt(21);
					if(!(continuationNum == '1'))
						continue;
					entry = parseWaypoint(line);
					break;
				} else if(line.charAt(5) == 'R') {
					// parseAirway one day
					continue;
				} else {
					continue;
				}
			default:
				continue;
			}
			if(entry == null)
				continue;

			// check for new ID- may  should also check type, since if id stays same
			//			if(prevId.equals(entry.id))
			//				continue;
//			System.err.println(entry.id+ "," + entry.name);
			entries.add(entry);
		}
		return entries;
	}
	
	public static List<NavDbEntry> filterEntries(List<NavDbEntry> entries, List<String> regions) throws IOException {
		List<NavDbEntry> filtered = new ArrayList<>();
		for(NavDbEntry e: entries) {
			if(regions.contains(e.region))
				filtered.add(e);
		}
		
		return filtered;
	}	

	public static List<NavDbEntry> filterEntries(List<NavDbEntry> entries, Type t) throws IOException {
		List<NavDbEntry> filtered = new ArrayList<>();
		for(NavDbEntry e: entries) {
			if(e.type == t)
				filtered.add(e);
		}
		
		return filtered;
	}	


	public static List<String> getDeltaIcaos(String filterPath) throws FileNotFoundException, IOException {
		List<String> delta = new ArrayList<>();
		try(BufferedReader br= new BufferedReader(new FileReader(filterPath))) {
			while(true) {
				String l = br.readLine();
				if(l==null)  break;
				String icao = l.substring(0, 4);
				delta.add(icao);
			}
		}
		return delta;
	}

	public static List<NavDbEntry> getDeltaAirports(String dbPath, String deltaPath) throws Exception {
		List<String> regions = new ArrayList<>();
		List<NavDbEntry> airports = getNavDbEntries(Paths.get(dbPath));
		List<String> icaos = getDeltaIcaos(deltaPath);
		List<NavDbEntry> deltaAirports = new ArrayList<>();
		for(NavDbEntry a: airports) {
			if(icaos.contains(a.id) && a.type == Type.AIRPORT) {
				//				System.err.println( a);
				deltaAirports.add(a);
			}
		}
		return deltaAirports;
	}

	public static void main(String[] args) throws Exception {
		Path dbPath = Paths.get("C:/Users/tcook/root/sensorHub/delta/data/navDb/Lufthansa_ARINC424_1710_test.dat");
//		List<NavDbEntry> es = getNavDbEntries(dbPath);
//		List<String> regs = new ArrayList<String>();
//		regs.add("USA");
//		List<NavDbEntry> ft = filterEntries(es, regs);
//		List<NavDbEntry> tt = filterEntries(ft, Type.WAYPOINT);
//		for(NavDbEntry e: tt)
//			System.err.println(e);

		Path deltaPath = Paths.get("C:/Users/tcook/root/sensorHub/delta/data/navDb/DeltaAirportFilter.csv");
		List<NavDbEntry> ds = getDeltaAirports(dbPath.toString(), deltaPath.toString());
		for(NavDbEntry e: ds)
			System.err.println(e);
		
	}
}
