package surveyKPI;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

/*
This file is part of SMAP.

SMAP is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

SMAP is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with SMAP.  If not, see <http://www.gnu.org/licenses/>.

*/

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.smap.sdal.Utilities.Authorise;
import org.smap.sdal.Utilities.GeneralUtilityMethods;
import org.smap.sdal.Utilities.ResultsDataSource;
import org.smap.sdal.Utilities.SDDataSource;
import org.smap.sdal.Utilities.UtilityMethodsEmail;
import org.smap.sdal.managers.PDFManager;

/*
 * Authorises the user and then
 * Downloads a file
 */

@Path("/file/{filename}")
public class GetFile extends Application {
	
	Authorise a = null;
	Authorise aOrg = new Authorise(null, Authorise.ORG);
	
	private static Logger log =
			 Logger.getLogger(GetFile.class.getName());

	public GetFile() {
		ArrayList<String> authorisations = new ArrayList<String> ();	
		authorisations.add(Authorise.ANALYST);
		authorisations.add(Authorise.ADMIN);
		authorisations.add(Authorise.ENUM);
		a = new Authorise(authorisations, null);	
	}
	
	@GET
	@Path("/organisation")
	@Produces("application/x-download")
	public Response getOrganisationFile (
			@Context HttpServletRequest request, 
			@Context HttpServletResponse response,
			@PathParam("filename") String filename,
			@QueryParam("settings") boolean settings,
			@QueryParam("org") int requestedOrgId) throws Exception {

		try {
		    Class.forName("org.postgresql.Driver");	 
		} catch (ClassNotFoundException e) {
			log.log(Level.SEVERE, "Can't find PostgreSQL JDBC Driver", e);
		    throw new Exception("Can't find PostgreSQL JDBC Driver");
		}
		
		int oId = 0;
		Response r = null;
		
		// Authorisation - Access
		Connection connectionSD = SDDataSource.getConnection("getFile");	
		a.isAuthorised(connectionSD, request.getRemoteUser());		
		try {		
			oId = GeneralUtilityMethods.getOrganisationId(connectionSD, request.getRemoteUser());
		} catch(Exception e) {
			// ignore error
		}
		if(requestedOrgId > 0 && requestedOrgId != oId) {
			aOrg.isAuthorised(connectionSD, request.getRemoteUser());	// Must be org admin to work on another organisations data
			oId = requestedOrgId;
		}
		// End Authorisation 
		
		log.info("Get File: " + filename + " for organisation: " + oId);
		try {
			String basepath = GeneralUtilityMethods.getBasePath(request);
			String filepath = basepath + "/media/organisation/" + oId + (settings ? "/settings/" : "/") + filename;
			System.out.println("Getting file: " + filepath);
			getFile(response, filepath, filename);
			
			r = Response.ok("").build();
			
		}  catch (Exception e) {
			log.info("Error getting file:" + e.getMessage());
			r = Response.serverError().build();
		} finally {	
			SDDataSource.closeConnection("getFile", connectionSD);	
		}
		
		return r;
	}
	
	@GET
	@Path("/users")
	@Produces("application/x-download")
	public Response getUsersFile (
			@Context HttpServletRequest request, 
			@Context HttpServletResponse response,
			@PathParam("filename") String filename,
			@QueryParam("type") String type) throws Exception {

		try {
		    Class.forName("org.postgresql.Driver");	 
		} catch (ClassNotFoundException e) {
			log.log(Level.SEVERE, "Can't find PostgreSQL JDBC Driver", e);
		    throw new Exception("Can't find PostgreSQL JDBC Driver");
		}
		
		int uId = 0;
		Response r = null;
		
		// Authorisation - Access
		Connection connectionSD = SDDataSource.getConnection("getFile");	
		a.isAuthorised(connectionSD, request.getRemoteUser());		
		try {		
			uId = GeneralUtilityMethods.getUserId(connectionSD, request.getRemoteUser());
		} catch(Exception e) {
			// ignore error
		}
		// End Authorisation 
		
		// Only allow valid categories of files
		if(type != null) {
			if(!type.equals("sig")) {
				type = null;
			}
		}
		
		log.info("Get File: " + filename + " for user: " + uId);
		try {
			String basepath = GeneralUtilityMethods.getBasePath(request);
			String filepath = basepath + "/media/" + uId + "/" + (type != null ? (type + "/") : "") + filename;
			log.info("Getting user file: " + filepath);
			getFile(response, filepath, filename);
			
			r = Response.ok("").build();
			
		}  catch (Exception e) {
			log.info("Error getting file:" + e.getMessage());
			r = Response.serverError().build();
		} finally {	
			SDDataSource.closeConnection("getFile", connectionSD);	
		}
		
		return r;
	}
	
	@GET
	@Path("/survey/{sId}")
	@Produces("application/x-download")
	public Response getSurveyFile (
			@Context HttpServletRequest request, 
			@Context HttpServletResponse response,
			@PathParam("filename") String filename,
			@PathParam("sId") int sId,
			@QueryParam("linked") boolean linked) throws Exception {

		try {
		    Class.forName("org.postgresql.Driver");	 
		} catch (ClassNotFoundException e) {
			log.log(Level.SEVERE, "Can't find PostgreSQL JDBC Driver", e);
		    throw new Exception("Can't find PostgreSQL JDBC Driver");
		}
		
		log.info("Get File: " + filename + " for survey: " + sId);
		
		Response r = null;
		
		// Authorisation - Access
		Connection connectionSD = SDDataSource.getConnection("getFile");	
		a.isAuthorised(connectionSD, request.getRemoteUser());
		a.isValidSurvey(connectionSD, request.getRemoteUser(), sId, false);
		// End Authorisation 
		
		try {
			String basepath = GeneralUtilityMethods.getBasePath(request);
			String sIdent = GeneralUtilityMethods.getSurveyIdent(connectionSD, sId);
			String filepath = basepath + "/media/" + sIdent+ "/" + filename;
			System.out.println("Getting file: " + filepath + " linked is: " + linked);
			
			if(linked && filename.indexOf('_') > 0) {
				// Create file if it is out of date
				createLinkedFile(connectionSD, sId, filename, filepath);
			}
			getFile(response, filepath, filename);
			
			r = Response.ok("").build();
			
		}  catch (Exception e) {
			log.info("Error getting file:" + e.getMessage());
			r = Response.serverError().build();
		} finally {	
			SDDataSource.closeConnection("getFile", connectionSD);	
		}
		
		return r;
	}
	
	
	/*
	 * Add the file to the response stream
	 */
	private void getFile(HttpServletResponse response, String filepath, String filename) throws IOException {
		
		File f = new File(filepath);
		response.setContentType(UtilityMethodsEmail.getContentType(filename));
		response.addHeader("Content-Disposition", "attachment; filename=" + filename);
		response.setContentLength((int) f.length());
		
		FileInputStream fis = new FileInputStream(f);
		OutputStream responseOutputStream = response.getOutputStream();
		
		int bytes;
		while ((bytes = fis.read()) != -1) {
			responseOutputStream.write(bytes);
		}
		fis.close();
	}
	
	/*
	 * Create the linked file
	 */
	private void createLinkedFile(Connection sd, int sId, String filename, String filepath) {
		
		ResultSet rs = null;
		
		String sqlAppearance = "select q_id, appearance from question "
				+ "where f_id in (select f_id from form where s_id = ?) "
				+ "and appearance is not null;";
		PreparedStatement pstmtAppearance = null;
		
		String sqlCalculate = "select q_id, calculate from question "
				+ "where f_id in (select f_id from form where s_id = ?) "
				+ "and calculate is not null;";
		PreparedStatement pstmtCalculate = null;
		
		try {
			
			/*
			 * Get the columns from the linked file
			 */
			ArrayList<String> uniqueColumns = new ArrayList<String> ();
			
			// Get columns from appearance
			pstmtAppearance = sd.prepareStatement(sqlAppearance);
			pstmtAppearance.setInt(1, sId);
			rs = pstmtAppearance.executeQuery();
			while(rs.next()) {
				System.out.println("Appearance: " + rs.getString(2));
				int qId = rs.getInt(1);
				String appearance = rs.getString(2);
				ArrayList<String> columns = GeneralUtilityMethods.getManifestParams(sd, qId, appearance,  filename, true);
				if(columns != null) {
					for (String col : columns) {
						if(!uniqueColumns.contains(col)) {
							uniqueColumns.add(col);
						}
					}
				}
			}
			
			// Get columns from calculate
			pstmtCalculate = sd.prepareStatement(sqlCalculate);
			pstmtCalculate.setInt(1, sId);
			rs = pstmtCalculate.executeQuery();
			while(rs.next()) {
				System.out.println("Calculate: " + rs.getString(2));
				int qId = rs.getInt(1);
				String calculate = rs.getString(2);
				ArrayList<String> columns = GeneralUtilityMethods.getManifestParams(sd, qId, calculate,  filename, false);
				if(columns != null) {
					for (String col : columns) {
						if(!uniqueColumns.contains(col)) {
							uniqueColumns.add(col);
						}
					}
				}
			}
			
			System.out.println("Unique columns: " + uniqueColumns.toString());
			
			// Get the survey ident that is going to provide the CSV data
			int idx = filename.indexOf('_');
			String sIdent = filename.substring(idx + 1);
			String sql = getSql(sd, sIdent, uniqueColumns);
			
			System.out.println("SQL: " + sql);
			

		} catch (Exception e) {
			
		} finally {
			if(pstmtAppearance != null) try{pstmtAppearance.close();}catch(Exception e) {}
			if(pstmtCalculate != null) try{pstmtCalculate.close();}catch(Exception e) {}
		}
	}
	
	/*
	 * Get the SQL to retrieve dynamic CSV data
	 */
	private String getSql(Connection sd, String sIdent, ArrayList<String> qnames) throws SQLException  {
		
		StringBuffer sql = new StringBuffer("select distinct ");
		StringBuffer where = new StringBuffer("");
		StringBuffer tabs = new StringBuffer("");
		int sId = 0;
		
		ResultSet rs = null;
		String sqlGetCol = "select column_name from question "
				+ "where qname = ? "
				+ "and f_id in (select f_id from form where s_id = ?)";
		PreparedStatement pstmtGetCol = null;
		
		String sqlGetTable = "select f_id, table_name from form "
				+ "where s_id = ? "
				+ "and parentform = ?";
		PreparedStatement pstmtGetTable = null;
		
		try {
			// 1. Get the survey id
			sId = GeneralUtilityMethods.getSurveyId(sd, sIdent);
			
			// 2. Add the columns
			pstmtGetCol = sd.prepareStatement(sqlGetCol);
			pstmtGetCol.setInt(2,  sId);
			
			for(int i = 0; i < qnames.size(); i++) {
				String name = qnames.get(i);
				pstmtGetCol.setString(1, name);
				rs = pstmtGetCol.executeQuery();
				if(rs.next()) {
					if(i > 0) {
						sql.append(",");
					}
					sql.append(rs.getString(1));
					sql.append(" as ");
					sql.append(name);
				}
			}
			
			// 3. Add the tables
			sql.append(" from ");
			pstmtGetTable = sd.prepareStatement(sqlGetTable);
			pstmtGetTable.setInt(1,  sId);
			getTables(pstmtGetTable, 0, tabs, where);
			sql.append(tabs);
			sql.append(where);
			
		} finally {
			if(pstmtGetCol != null) try {pstmtGetCol.close();} catch(Exception e) {}
			if(pstmtGetTable != null) try {pstmtGetTable.close();} catch(Exception e) {}
		}
		return sql.toString();
	}
	
	/*
	 * Get table details
	 */
	private void getTables(PreparedStatement pstmt, int parent, StringBuffer tabs, StringBuffer where) throws SQLException {
		ResultSet rs = null;
		pstmt.setInt(2, parent);
		rs = pstmt.executeQuery();
		while(rs.next()) {
			int fId = rs.getInt(1);
			String table = rs.getString(2);
			
			if(tabs.length() > 0) {
				tabs.append(",");
			}
			tabs.append(table);
		}
	}

}