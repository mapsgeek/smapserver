package koboToolboxApi;
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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import managers.DataManager;
import model.DataEndPoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import org.smap.sdal.managers.SurveyManager;
import org.smap.sdal.model.Column;
import org.smap.sdal.model.Form;
import org.smap.sdal.model.Survey;

/*
 * Returns data for the passed in table name
 */
@Path("/v1/data.csv")
public class Data_CSV extends Application {
	
	Authorise a = null;
	Authorise aRecords = new Authorise(null, Authorise.ANALYST);
	
	private static Logger log =
			 Logger.getLogger(Data_CSV.class.getName());

	// Tell class loader about the root classes.  (needed as tomcat6 does not support servlet 3)
	public Set<Class<?>> getClasses() {
		Set<Class<?>> s = new HashSet<Class<?>>();
		s.add(Data_CSV.class);
		return s;
	}
	
	public Data_CSV() {
		ArrayList<String> authorisations = new ArrayList<String> ();	
		authorisations.add(Authorise.ANALYST);
		authorisations.add(Authorise.ADMIN);
		authorisations.add(Authorise.ENUM);
		a = new Authorise(authorisations, null);
	}
	
	/*
	 * KoboToolBox API version 1 /data.csv
	 * CSV version
	 */
	@GET
	public void getDataCsv(@Context HttpServletRequest request,
			@Context HttpServletResponse response) { 
		
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection("surveyKPI-Surveys");
		a.isAuthorised(sd, request.getRemoteUser());
		
		StringBuffer record = null;
		PrintWriter outWriter = null;
		
		try {
		    Class.forName("org.postgresql.Driver");	 
		} catch (ClassNotFoundException e) {
			log.log(Level.SEVERE, "Can't find PostgreSQL JDBC Driver", e);
			try {response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);} catch(Exception ex) {};
		}
		
		DataManager dm = new DataManager();
		try {
			ArrayList<DataEndPoint> data = dm.getDataEndPoints(sd, request, true);
			
			outWriter = response.getWriter();
			String basePath = GeneralUtilityMethods.getBasePath(request);
		
			if(data.size() > 0) {
				for(int i = 0; i < data.size(); i++) {
					DataEndPoint dep = data.get(i);
					if(i == 0) {
						outWriter.print(dep.getCSVColumns() + "\n");
					}
					outWriter.print(dep.getCSVData() + "\n");
			
				}
			} else {
				outWriter.print("No Data");
			}
			
			outWriter.flush(); 
			outWriter.close();
			
		} catch (Exception e) {
			e.printStackTrace();
			try {response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);} catch(Exception ex) {};
		}


	}
	
	/*
	 * KoboToolBox API version 1 /data
	 */
	@GET
	@Produces("application/json")
	@Path("/{sId}")
	public void getDataRecords(@Context HttpServletRequest request,
			@Context HttpServletResponse response,
			@PathParam("sId") int sId,
			@QueryParam("start") int start,
			@QueryParam("limit") int limit) { 
		
		// Authorisation - Access
		Connection sd = SDDataSource.getConnection("koboToolboxApi - get data records csv");
		aRecords.isAuthorised(sd, request.getRemoteUser());
		aRecords.isValidSurvey(sd, request.getRemoteUser(), sId, false);
		// End Authorisation
		
		Connection cResults = ResultsDataSource.getConnection("koboToolboxApi - get data records csv");
		
		String sqlGetMainForm = "select f_id, table_name from form where s_id = ? and parentform = 0;";
		PreparedStatement pstmtGetMainForm = null;
		PreparedStatement pstmtGetData = null;
		
		StringBuffer columnSelect = new StringBuffer();
		StringBuffer record = null;
		PrintWriter outWriter = null;

		try {
			outWriter = response.getWriter();
			String basePath = GeneralUtilityMethods.getBasePath(request);
			
			pstmtGetMainForm = sd.prepareStatement(sqlGetMainForm);
			pstmtGetMainForm.setInt(1,sId);
			
			log.info("Getting main form: " + pstmtGetMainForm.toString() );
			ResultSet rs = pstmtGetMainForm.executeQuery();
			if(rs.next()) {
				int fId = rs.getInt(1);
				String table_name = rs.getString(2);
				
				ArrayList<Column> columns = GeneralUtilityMethods.getColumnsInForm(
						sd,
						cResults,
						0,			// parent form
						fId,
						table_name,
						false,
						false,		// Don't include parent key
						false,		// Don't include "bad" columns
						false		// Don't include instance id
						);
				
				for(int i = 0; i < columns.size(); i ++) {
					Column c = columns.get(i);
					if(i > 0) {
						columnSelect.append(",");
					}
					columnSelect.append(c.name);
				}
				
				if(GeneralUtilityMethods.tableExists(cResults, table_name)) {
					
					outWriter.print(columnSelect.toString() + "\n");
					
					String sqlGetData = "select " + columnSelect.toString() + " from " + table_name
							+ " where prikey >= ?"
							+ " order by prikey asc;";
					
					pstmtGetData = cResults.prepareStatement(sqlGetData);
					pstmtGetData.setInt(1, start);
					
					log.info("Get CSV data: " + pstmtGetData.toString());
					rs = pstmtGetData.executeQuery();
					
					int index = 0;
					while (rs.next()) {
						record = new StringBuffer();
						
						for(int i = 0; i < columns.size(); i++) {
							Column c = columns.get(i);
							if(i > 0) {
								record.append(",");
							}
							record.append("\"" + rs.getString(i + 1).replaceAll("\"", "\"\"") + "\"");
						}
						
						record.append("\n");
						outWriter.print(record.toString());
						
						if(limit > 0 && index >= index) {
							break;
						}
					}
				} else {
					outWriter.print("No data\n");
				}
				
				outWriter.flush(); 
				outWriter.close();
				
			}
			
			
		} catch (Exception e) {
			log.log(Level.SEVERE, "Exception", e);
			try {response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);} catch(Exception ex) {};
		} finally {
			
			try {if (pstmtGetMainForm != null) {pstmtGetMainForm.close();	}} catch (SQLException e) {	}
			
			try {
				if (cResults != null) {
					cResults.close();
					cResults = null;
				}
			} catch (SQLException e) {
				log.log(Level.SEVERE, "Failed to close results connection", e);
			}
			
			try {
				if (sd != null) {
					sd.close();
					sd = null;
				}
			} catch (SQLException e) {
				log.log(Level.SEVERE, "Failed to close connection", e);
			}
		}
		

	}
	
	
}
