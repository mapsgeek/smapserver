package org.smap.sdal.managers;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.smap.sdal.Utilities.GeneralUtilityMethods;
import org.smap.sdal.Utilities.UtilityMethodsEmail;
import org.smap.sdal.model.ChangeItem;
import org.smap.sdal.model.ChangeSet;
import org.smap.sdal.model.Label;
import org.smap.sdal.model.Option;
import org.smap.sdal.model.PropertyChange;
import org.smap.sdal.model.Question;
import org.smap.sdal.model.Survey;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/*****************************************************************************

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

 ******************************************************************************/

public class QuestionManager {
	
	private static Logger log =
			 Logger.getLogger(QuestionManager.class.getName());

	/*
	 * These functions are used when adding CSV files. 
	 * They will add the choices from the CSV files to the survey definition
	 */
	public ArrayList<Question> getByCSV(Connection sd, 
			int sId,
			String csvFileName			
			)  {
		
		ArrayList<Question> questions = new ArrayList<Question>();	// Results of request
		
		
		Survey survey = new Survey();
		survey.setId(sId);
		
		int idx = csvFileName.lastIndexOf('.');
		String csvRoot = csvFileName;
		if(idx > 0) {
			csvRoot = csvFileName.substring(0, idx);
		}
		
		// Escape csvRoot
		csvRoot = csvRoot.replace("\'", "\'\'");
		
		ResultSet resultSet = null;
		String sql = "select q.q_id, q.qname, q.qtype, q.appearance, q.l_id " +
				" from question q, form f " +
				" where f.f_id = q.f_id " +
				" and q.appearance like '%search(''" + csvRoot + "''%' " +
				" and q.qtype like 'select%' " + 
				" and f.s_id = ?";
	
		String sqlOption = "select o.o_id, o.seq, o.label_id, o.ovalue " +
				" from option o, question q" +
				" where q.q_id = ? " +
				" and o.l_id = 1.l_id" +
				" and externalfile = 'true';";

		PreparedStatement pstmt = null;
		PreparedStatement pstmtOption = null;
		
		try {
			pstmtOption = sd.prepareStatement(sqlOption);
			
			survey.languages = GeneralUtilityMethods.getLanguages(sd, sId);
			
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, sId);
			
			log.info("Get questions for CSV: " + pstmt.toString());
			resultSet = pstmt.executeQuery();
	
			while (resultSet.next()) {								
	
				Question q = new Question();
				q.id = resultSet.getInt(1);
				q.name = resultSet.getString(2);
				q.type = resultSet.getString(3);
				q.appearance = resultSet.getString(4);
				q.l_id = resultSet.getInt(5);
				
				questions.add(q);
			} 
		} catch (Exception e) {
			log.log(Level.SEVERE,"Error", e);
		} finally {
			if(pstmt != null) try{pstmt.close();}catch(Exception e){}
			if(pstmtOption != null) try{pstmtOption.close();}catch(Exception e){}
		}
		
		return questions;
		
	}
	

	/*
	 * Save a new question
	 */
	public void save(Connection sd, Connection cResults, int sId, ArrayList<Question> questions) throws Exception {
		
		String columnName = null;
		SurveyManager sm = new SurveyManager();		// To apply survey level updates resulting from this question change
		
		PreparedStatement pstmtInsertQuestion = null;
		String sql = "insert into question (q_id, "
				+ "f_id, "
				+ "l_id, "
				+ "seq, "
				+ "qname, "
				+ "column_name, "
				+ "qtype, "
				+ "qtext_id, "
				+ "infotext_id, "
				+ "source, "
				+ "calculate, "
				+ "defaultanswer, "
				+ "appearance, "
				+ "visible, "
				+ "path, "
				+ "readonly, "
				+ "relevant, "
				+ "qconstraint, "
				+ "constraint_msg, "
				+ "required_msg, "
				+ "autoplay, "
				+ "accuracy,"
				+ "nodeset,"
				+ "nodeset_value,"
				+ "nodeset_label,"
				+ "display_name"
				+ ") " 
				+ "values (nextval('q_seq'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ? ,?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
		
		PreparedStatement pstmtUpdateSeq = null;
		String sqlUpdateSeq = "update question set seq = seq + 1 where f_id = ? and seq >= ?;";
		
		PreparedStatement pstmtForm = null;
		String sqlForm = "insert into form(f_id, s_id, name, label, table_name, "
				+ "parentform, parentquestion, repeats, path, form_index) " +
				"values(nextval('f_seq'), ?, ?, ?, ?, ?, ?, ?, ?, ?);";
		
		PreparedStatement pstmtGetFormId = null;
		String sqlGetFormId = "select f_id, path from form where s_id = ? and form_index = ?;";
		
		PreparedStatement pstmtGetOldQuestions = null;
		String sqlGetOldQuestions = "select column_name from question q where q.f_id = ? and q.qname = ? and q.soft_deleted = 'true';";
		
		try {
			pstmtUpdateSeq = sd.prepareStatement(sqlUpdateSeq);
			pstmtInsertQuestion = sd.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			pstmtGetOldQuestions = sd.prepareStatement(sqlGetOldQuestions);
			
			for(Question q : questions) {
				
				if(q.fId <= 0) {	// New Form, the formIndex can be used to retrieve the formId of this new form
					
					pstmtGetFormId = sd.prepareStatement(sqlGetFormId);
					pstmtGetFormId.setInt(1, sId);
					pstmtGetFormId.setInt(2, q.formIndex);
					
					log.info("SQL: Get form id: " + pstmtGetFormId.toString());
					ResultSet rs = pstmtGetFormId.executeQuery();
					rs.next();
					q.fId = rs.getInt(1);
				}
				
				if(q.type.startsWith("select")) {	// Get the list id
					q.l_id = GeneralUtilityMethods.getListId(sd, sId, q.list_name);
				} else if(q.type.equals("calculate")) {
					q.type = "string";
					q.visible = false;
				} else if(q.type.equals("begin repeat") 
						|| q.type.equals("begin group")
						|| q.type.equals("end group")) {
					q.source = null;
				}
				
				boolean isRepeatType = false;
				if(q.type.equals("begin repeat") || q.type.equals("geopolygon") || q.type.equals("geolinestring")) {
					isRepeatType = true;
				}
				
				// First reorder questions in the target form so the sequence starts from 0 and increments by 1 each time 
				// as the editor expects
				GeneralUtilityMethods.cleanQuestionSequences(sd, q.fId);
				
				// Update sequence numbers of questions after the question to be inserted
				pstmtUpdateSeq.setInt(1, q.fId);
				pstmtUpdateSeq.setInt(2, q.seq);
				
				log.info("Update sequences: " + pstmtUpdateSeq.toString());
				pstmtUpdateSeq.executeUpdate();
				
				// If there is a soft deleted question with the same name and question type in the form then delete it
				pstmtGetOldQuestions.setInt(1, q.fId);
				pstmtGetOldQuestions.setString(2,  q.name);
				ResultSet rs = pstmtGetOldQuestions.executeQuery();
				if(rs.next()) {
					
					columnName = rs.getString(1);	// Reuse column name as we won't be recreating column in results table
					
					ArrayList<Question> oldQuestions = new ArrayList<Question> ();
					Question oldQ = new Question();
					oldQ.fId = q.fId;
					oldQ.name = q.name;
					oldQuestions.add(oldQ);
					delete(sd, cResults, sId, oldQuestions, true, true);	// Force the delete as we are replacing the question
				}
				
				String type = GeneralUtilityMethods.translateTypeToDB(q.type, q.name);
				String source = q.source;
				if(type.equals("geopolygon") || type.equals("geolinestring")) {
					source = "user";
					columnName = "the_geom";
				}
				boolean readonly = GeneralUtilityMethods.translateReadonlyToDB(q.type, q.readonly);
			
				// Insert the question
				if(columnName == null) {
					columnName = GeneralUtilityMethods.cleanName(q.name, true, true, true);
				}
				
				// Assume that every question has a label, however hints are optional (to reduce size of form)
				String infotextId = null;
				for(Label l : q.labels) {
					if(l.hint != null && l.hint.trim().length() > 0) {
						infotextId = q.fId + "_question_" + columnName + ":hint";
					}
				}

				pstmtInsertQuestion.setInt(1, q.fId );
				pstmtInsertQuestion.setInt(2, q.l_id);
				pstmtInsertQuestion.setInt(3, q.seq );
				pstmtInsertQuestion.setString(4, q.name );
				pstmtInsertQuestion.setString(5, columnName);
				pstmtInsertQuestion.setString(6, type );
				pstmtInsertQuestion.setString(7, q.fId + "_question_" + columnName + ":label" );
				pstmtInsertQuestion.setString(8, infotextId );
				pstmtInsertQuestion.setString(9, source );
				if(isRepeatType) {
					pstmtInsertQuestion.setString(10,  null);	// Calculation goes in form
				} else {
					pstmtInsertQuestion.setString(10,  q.calculation);
				}
				pstmtInsertQuestion.setString(11, q.defaultanswer );
				pstmtInsertQuestion.setString(12, q.appearance);
				pstmtInsertQuestion.setBoolean(13, q.visible);
				pstmtInsertQuestion.setString(14, "");	// path is no longer used
				pstmtInsertQuestion.setBoolean(15, readonly);
				pstmtInsertQuestion.setString(16, q.relevant);
				pstmtInsertQuestion.setString(17, q.constraint);
				pstmtInsertQuestion.setString(18, q.constraint_msg);
				pstmtInsertQuestion.setString(19, q.required_msg);
				pstmtInsertQuestion.setString(20, q.autoplay);
				pstmtInsertQuestion.setString(21, q.accuracy);
				
				String nodeset = null;
				String nodeset_value = null;
				String nodeset_label = null;
				String cascade_instance = null;
				
				if(q.type.startsWith("select")) {
					cascade_instance = GeneralUtilityMethods.cleanName(q.list_name, true, false, false);
					nodeset = GeneralUtilityMethods.getNodesetFromChoiceFilter(q.choice_filter, cascade_instance);
					nodeset_value = "name";
					nodeset_label = "jr:itext(itextId)";
				}
				pstmtInsertQuestion.setString(22, nodeset);
				pstmtInsertQuestion.setString(23, nodeset_value);
				pstmtInsertQuestion.setString(24, nodeset_label);
				pstmtInsertQuestion.setString(25, q.display_name);
				
				log.info("Insert question: " + pstmtInsertQuestion.toString());
				pstmtInsertQuestion.executeUpdate();
				
				// Set the labels
				if(q.name != null && q.name.trim().length() > 0 && !q.type.equals("end group")) {
					UtilityMethodsEmail.setLabels(sd, sId, q.fId + "_question_" + columnName, q.labels, "");
				}
				
				// Update the survey manifest if this question references CSV files
				sm.updateSurveyManifest(sd, sId, q.appearance, q.calculation);
				
				// If this is a begin repeat then create a new form
				if(isRepeatType) {
					
					rs = pstmtInsertQuestion.getGeneratedKeys();
					rs.next();
					int qId = rs.getInt(1);
					
					// Create the sub form
					String tableName = "s" + sId + "_" + columnName;
			
					pstmtForm = sd.prepareStatement(sqlForm);
					pstmtForm.setInt(1, sId);
					pstmtForm.setString(2, q.name);
					pstmtForm.setString(3, q.fId + "_question_" + columnName + ":label");
					pstmtForm.setString(4, tableName);
					pstmtForm.setInt(5, q.fId);
					pstmtForm.setInt(6, qId);		// parent question id
					pstmtForm.setString(7, q.calculation);
					pstmtForm.setString(8, "");	// path is no longer used
					pstmtForm.setInt(9, q.childFormIndex);
					
					log.info("SQL: Insert new form: " + pstmtForm.toString());
					pstmtForm.executeUpdate();
					
				}
				
			}
			
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtUpdateSeq != null) {pstmtUpdateSeq.close();}} catch (SQLException e) {}
			try {if (pstmtInsertQuestion != null) {pstmtInsertQuestion.close();}} catch (SQLException e) {}
			try {if (pstmtForm != null) {pstmtForm.close();}} catch (SQLException e) {}
			try {if (pstmtGetFormId != null) {pstmtGetFormId.close();}} catch (SQLException e) {}
			try {if (pstmtGetOldQuestions != null) {pstmtGetOldQuestions.close();}} catch (SQLException e) {}
		}
		
	}
	
	
	/*
	 * Move Questions
	 * If the question type is a begin group or end group then all the questions within the group will also be moved
	 */
	public void moveQuestions(Connection sd, int sId, ArrayList<Question> questions) throws Exception {
		
		//String newGroupPath;
		//String oldGroupPath;
		//String newPath;
		ArrayList<Question> questionsInGroup = null;
		
		
		for(Question q : questions) {
		
			log.info("Move a question: " + q.name + " : " + q.type);

			if(q.type.equals("begin group")) {
				
				// Move every question in this group
				questionsInGroup = getQuestionsInGroup(sd, q, true);
				for(Question groupQuestion : questionsInGroup) {
					moveAQuestion(sd, sId, groupQuestion, true);
				}
			} else {	
				moveAQuestion(sd, sId, q, false);
			}
		}
	}
	
	/*
	 * Get all the questions in a group
	 */
	private ArrayList<Question> getQuestionsInGroup(Connection sd, Question q, boolean inclusive) throws Exception {
		
		ArrayList<Question> questions = new ArrayList<Question> ();
		int startSeq;
		int endSeq;
		int newSeq;
		
		String sqlGetSeq = "select seq from question where f_id = ? and qname = ?;";
		PreparedStatement pstmtGetSeq = null;
		
		PreparedStatement pstmt = null;
		String sqlInclusive = "select qname, path, qType, seq from question q where f_id = ? "
				+ "and seq >= ? "
				+ "and seq <= ? "
				+ "order by seq asc;";
		String sqlExclusive = "select qname, path, qType, seq from question q where f_id = ? "
				+ "and seq > ? "
				+ "and seq < ? "
				+ "order by seq asc;";
		
		try {
			
			/*
			 * If source form id is 0 then this request is for a group being deleted rather than moved
			 */
			int formId = 0;
			if(q.sourceFormId == 0) {
				formId = q.fId;
			} else {
				formId = q.sourceFormId;
			}
			
			pstmtGetSeq = sd.prepareStatement(sqlGetSeq);
			
			// Get start sequence
			pstmtGetSeq.setInt(1, formId);
			pstmtGetSeq.setString(2, q.name);
			ResultSet rsSeq = pstmtGetSeq.executeQuery();
			if(rsSeq.next()) {
				startSeq = rsSeq.getInt(1);
			} else {
				throw new Exception("Start Sequence Not Found");
			}
			
			// Get end sequence
			rsSeq.close();
			pstmtGetSeq.setString(2, q.name + "_groupEnd");
			rsSeq = pstmtGetSeq.executeQuery();
			if(rsSeq.next()) {
				endSeq = rsSeq.getInt(1);
			} else {
				throw new Exception("End Sequence Not Found");
			}
			
			if(inclusive) {
				pstmt = sd.prepareStatement(sqlInclusive);
			} else {
				pstmt = sd.prepareStatement(sqlExclusive);
			}
			pstmt.setInt(1, formId);
			pstmt.setInt(2, startSeq);
			pstmt.setInt(3, endSeq);
			
			log.info("SQL Get questions in group: " + pstmt.toString());
			
			ResultSet rs = pstmt.executeQuery();
			newSeq = q.seq;											// The target sequence
			while(rs.next()) {
				
				Question groupQuestion = new Question();
				groupQuestion.sourceFormId = q.sourceFormId;
				groupQuestion.fId = q.fId;
				groupQuestion.seq = newSeq++;
				
				groupQuestion.name = rs.getString(1);
				//groupQuestion.path =  rs.getString(2);
				groupQuestion.type = rs.getString(3);
				groupQuestion.sourceSeq = rs.getInt(4);
				
				questions.add(groupQuestion);
			}
		} catch(SQLException e) {
			String msg = e.getMessage();
			if(msg == null || !msg.startsWith("Already modified")) {
				log.log(Level.SEVERE,"Error", e);
			}
			throw e;
		} 
		finally {
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
			try {if (pstmtGetSeq != null) {pstmtGetSeq.close();}} catch (SQLException e) {}
		}
		
		return questions;
		
	}
	
	/*
	 * Move a question
	 * This can only be called for questions that are already in the database as otherwise the move is merely added to the
	 *  question creation
	 */
	public void moveAQuestion(Connection sd, int sId, Question q, boolean ignoreExistingSeq ) throws Exception {
		
		PreparedStatement pstmtMoveWithin = null;
		String sqlMoveWithin = "update question set "
						+ "seq = ? "
					+ "where f_id = ? "
					+ "and qname = ? "
					+ "and seq = ?;";
		
		PreparedStatement pstmtMoveWithinIgnoreExistingSeq = null;
		String sqlMoveWithinIgnoreExistingSeq = "update question set "
						+ "seq = ? "
					+ "where f_id = ? "
					+ "and qname = ?;";

		
		PreparedStatement pstmtMovedBack = null;
		String sqlMovedBack = "update question set seq = seq + 1 where f_id = ? and seq >= ? and seq < ?;";
		
		PreparedStatement pstmtMovedForward = null;
		String sqlMovedForward = "update question set seq = seq - 1 where f_id = ? and seq > ? and seq <= ?;";
		
		PreparedStatement pstmtMovedToAnotherForm = null;
		String sqlMovedToAnotherForm = "update question set seq = -100, f_id = ? where f_id = ? and qname = ? and f_id in "
				+ "(select f_id from form where s_id = ?);";
		
		PreparedStatement pstmtGetFormId = null;
		String sqlGetFormId = "select f_id from form where s_id = ? and form_index = ?;";
		
		try {	
			
			// Get the formId of the target form
			pstmtGetFormId = sd.prepareStatement(sqlGetFormId);
			pstmtGetFormId.setInt(1, sId);
			if(q.fId <= 0) {	// New Form, the formIndex can be used to retrieve the formId of this new form
				
				pstmtGetFormId.setInt(2, q.formIndex);
				
				log.info("SQL: Get form id: " + pstmtGetFormId.toString());
				ResultSet rs = pstmtGetFormId.executeQuery();
				rs.next();
				q.fId = rs.getInt(1);	
			}
			
			// Get the formId of the source form
			if(q.sourceFormId <= 0) {	// New Form, the formIndex can be used to retrieve the formId of this new form
				
				pstmtGetFormId.setInt(2, q.sourceFormIndex);
				
				log.info("SQL: Get source form id: " + pstmtGetFormId.toString());
				ResultSet rs = pstmtGetFormId.executeQuery();
				rs.next();
				q.sourceFormId = rs.getInt(1);		
			}
			
			// First reorder questions in the target form so the sequence starts from 0 and increments by 1 each time 
			// as the editor expects
			GeneralUtilityMethods.cleanQuestionSequences(sd, q.fId);
			
			boolean moveWithinList = q.fId == q.sourceFormId;
			
			if(!moveWithinList) {
				// 1. Change the form id and set the sequence to -100
				
				q.sourceSeq = -100;
				
				pstmtMovedToAnotherForm = sd.prepareStatement(sqlMovedToAnotherForm);
				pstmtMovedToAnotherForm.setInt(1, q.fId);
				pstmtMovedToAnotherForm.setInt(2, q.sourceFormId);
				pstmtMovedToAnotherForm.setString(3, q.name);
				pstmtMovedToAnotherForm.setInt(4, sId);
				
				log.info("Move to another form: " + pstmtMovedToAnotherForm.toString());
				pstmtMovedToAnotherForm.executeUpdate();
				
				// 2. Reorder the questions in the old form
				GeneralUtilityMethods.cleanQuestionSequences(sd, q.sourceFormId);
			}
			
			/*
			 * Now move the question within its new form
			 */
			
			// Update sequence numbers of other question
			if(q.seq > q.sourceSeq) { // Moved forward in list
				
				pstmtMovedForward = sd.prepareStatement(sqlMovedForward);
				pstmtMovedForward.setInt(1,q.fId);
				pstmtMovedForward.setInt(2, q.sourceSeq);
				pstmtMovedForward.setInt(3, q.seq);
				
				log.info("Moving forward: " + pstmtMovedForward.toString());
				pstmtMovedForward.executeUpdate();
			} else {	// Moved backward in list
				
				pstmtMovedBack = sd.prepareStatement(sqlMovedBack);
				pstmtMovedBack.setInt(1, q.fId);
				pstmtMovedBack.setInt(2, q.seq);
				pstmtMovedBack.setInt(3, q.sourceSeq);
				
				log.info("Moving back: " + pstmtMovedBack.toString());
				pstmtMovedBack.executeUpdate();						
			}
			
			// Move the question
			int count = 0;
			if(ignoreExistingSeq) {
				pstmtMoveWithinIgnoreExistingSeq = sd.prepareStatement(sqlMoveWithinIgnoreExistingSeq);
				pstmtMoveWithinIgnoreExistingSeq.setInt(1, q.seq );
				pstmtMoveWithinIgnoreExistingSeq.setInt(2, q.fId );
				pstmtMoveWithinIgnoreExistingSeq.setString(3, q.name);
			
				log.info("Move question within same list: " + pstmtMoveWithinIgnoreExistingSeq.toString());
				count = pstmtMoveWithinIgnoreExistingSeq.executeUpdate();
			} else {
				pstmtMoveWithin = sd.prepareStatement(sqlMoveWithin);
				pstmtMoveWithin.setInt(1, q.seq );
				pstmtMoveWithin.setInt(2, q.fId );
				pstmtMoveWithin.setString(3, q.name);
				pstmtMoveWithin.setInt(4, q.sourceSeq );
			
				log.info("Move question within same list: " + pstmtMoveWithin.toString());
				count = pstmtMoveWithin.executeUpdate();
			}
			if(count == 0) {
				String msg = "Warning: question " + q.name + " was not moved. It may already have been moved by someone else";
				log.info(msg);
				throw new Exception(msg);		// No matching value assume it has already been modified
			}
			
		} catch(SQLException e) {
			
			String msg = e.getMessage();
			if(msg == null || !msg.startsWith("Warning")) {
				log.log(Level.SEVERE,"Error", e);
			}
			throw e;
		} finally {
			try {if (pstmtMoveWithin != null) {pstmtMoveWithin.close();}} catch (SQLException e) {}
			try {if (pstmtMoveWithinIgnoreExistingSeq != null) {pstmtMoveWithinIgnoreExistingSeq.close();}} catch (SQLException e) {}
			try {if (pstmtMovedBack != null) {pstmtMovedBack.close();}} catch (SQLException e) {}
			try {if (pstmtMovedForward != null) {pstmtMovedForward.close();}} catch (SQLException e) {}
			try {if (pstmtMovedToAnotherForm != null) {pstmtMovedToAnotherForm.close();}} catch (SQLException e) {}
			try {if (pstmtGetFormId != null) {pstmtGetFormId.close();}} catch (SQLException e) {}
		}	
		
	}
	
	/*
	 * Delete
	 */
	public void delete(Connection sd, Connection cResults,
			int sId, ArrayList<Question> questions, boolean force, 
			boolean getGroupContents) throws Exception {
		
		ArrayList<Question> groupContents = null;
		
		PreparedStatement pstmt = null;
		String sql = "delete from question q where f_id = ? and qname = ? and q.q_id in " +
				" (select q_id from question q, form f where q.f_id = f.f_id and f.s_id = ?);";	// Ensure user is authorised to access this question

		PreparedStatement pstmtSoftDelete = null;
		String sqlSoftDelete = "update question set soft_deleted = 'true' where f_id = ? and qname = ? and q_id in " +
				" (select q_id from question q, form f where q.f_id = f.f_id and f.s_id = ?);";	
		
		PreparedStatement pstmtDelLabels = null;
		String sqlDelLabels = "delete from translation t where t.s_id = ? and " +
				"t.text_id in (select qtext_id  from question where qname = ? and f_id = ? and q_id in " +
				" (select q.q_id from question q, form f where q.f_id = f.f_id and f.s_id = ?));";
		
		PreparedStatement pstmtDelHints = null;
		String sqlDelHints = "delete from translation t where t.s_id = ? and " +
				"t.text_id in (select infotext_id  from question where qname = ? and f_id = ? and q_id in " +
				" (select q.q_id from question q, form f where q.f_id = f.f_id and f.s_id = ?));";
		
		PreparedStatement pstmtUpdateSeq = null;
		String sqlUpdateSeq = "update question set seq = seq - 1 where f_id = ? and seq >= ? and f_id in " +
				"(select f_id from form where s_id = ?)";
		
		PreparedStatement pstmtGetSeq = null;
		String sqlGetSeq = "select seq, qtype, published from question where f_id = ? and qname = ?";
		
		PreparedStatement pstmtGetTableName = null;
		PreparedStatement pstmtDeleteForm = null;
		
		try {
			pstmtUpdateSeq = sd.prepareStatement(sqlUpdateSeq);
			pstmtDelLabels = sd.prepareStatement(sqlDelLabels);
			pstmtDelHints = sd.prepareStatement(sqlDelHints);
			pstmt = sd.prepareStatement(sql);
			pstmtGetSeq = sd.prepareStatement(sqlGetSeq);
			pstmtSoftDelete = sd.prepareStatement(sqlSoftDelete);
			
			for(Question q : questions) {
				
				int seq = 0;
				String qType = q.type;
				boolean published = false;
				
				/*
				 * Check to see if the question has been published, and get its sequence
				 */
				pstmtGetSeq.setInt(1, q.fId);
				pstmtGetSeq.setString(2, q.name );
				log.info("SQL get sequence: " + pstmtGetSeq.toString());
				ResultSet rs = pstmtGetSeq.executeQuery();
				if(rs.next()) {
					seq = rs.getInt(1);
					qType = rs.getString(2);
					published = rs.getBoolean(3);
				}
				
				/*
				 * If the question is a group question then get its members
				 */
				if(qType.equals("begin group") && getGroupContents) {
					groupContents = getQuestionsInGroup(sd, q, false);
				}
				
				if(published && !force) {
					/*
					 * The question has got some data associated with it in a results table
					 * It should only be soft deleted so that:
					 *   The editor can prevent another question from being added with the same name
					 *   The results data can be accessed if needed
					 */
					pstmtSoftDelete.setInt(1, q.fId);
					pstmtSoftDelete.setString(2, q.name );
					pstmtSoftDelete.setInt(3, sId );
					
					log.info("Soft Delete question: " + pstmtSoftDelete.toString());
					pstmtSoftDelete.executeUpdate();
				} else {
					// Properly delete the question
					
					// Delete the labels
					pstmtDelLabels.setInt(1, sId);
					pstmtDelLabels.setString(2, q.name );
					pstmtDelLabels.setInt(3, q.fId);
					pstmtDelLabels.setInt(4, sId );
					
					log.info("Delete question labels: " + pstmtDelLabels.toString());
					pstmtDelLabels.executeUpdate();
					
					// Delete the hints
					pstmtDelHints.setInt(1, sId);
					pstmtDelHints.setString(2, q.name );
					pstmtDelHints.setInt(3, q.fId);
					pstmtDelHints.setInt(4, sId );
					
					log.info("Delete question hints: " + pstmtDelHints.toString());
					pstmtDelHints.executeUpdate();
					
					/*
					 * Delete the question
					 */
					pstmt.setInt(1, q.fId);
					pstmt.setString(2, q.name );
					pstmt.setInt(3, sId );
					
					log.info("Delete question: " + pstmt.toString());
					pstmt.executeUpdate();
					
					// Update the sequences of questions after the deleted question
					pstmtUpdateSeq.setInt(1, q.fId);
					pstmtUpdateSeq.setInt(2, seq);
					pstmtUpdateSeq.setInt(3, sId);
					
					log.info("Update sequences: " + pstmtUpdateSeq.toString());
					pstmtUpdateSeq.executeUpdate();
				}
				
				/*
				 * If the question is a group question then either:
				 *   delete all the contents of the group, or
				 *   Just remove the group so that the contents of the group are empty - TODO
				 */
				
				// If the question is a group question then also delete the end group
				if(qType.equals("begin group")) {
					String endGroupName = q.name + "_groupEnd";
					
					pstmtGetSeq.setString(2, endGroupName );
					rs = pstmtGetSeq.executeQuery();
					if(rs.next()) {
						seq = rs.getInt(1);
						
						// Delete the labels
						pstmtDelLabels.setInt(1, sId);
						pstmtDelLabels.setString(2, endGroupName );
						pstmtDelLabels.setInt(3, q.fId);
						pstmtDelLabels.setInt(4, sId );
						
						log.info("Delete end group labels: " + pstmtDelLabels.toString());
						pstmtDelLabels.executeUpdate();
						
						// Delete the end group
						pstmt.setString(2, endGroupName);
						
						log.info("Delete End group of question: " + pstmt.toString());
						pstmt.executeUpdate();
						
						// Update the sequences of questions after the deleted end group
						pstmtUpdateSeq.setInt(2, seq);
						
						log.info("Update sequences: " + pstmtUpdateSeq.toString());
						pstmtUpdateSeq.executeUpdate();
					}
					
					/*
					 * Delete the contents of the group
					 */
					if(groupContents != null) {
						delete(sd, cResults, sId, groupContents, force, false);
					}
				}
				
				/*
				 * If the question is a repeat question then either:
				 *   delete the form, or
				 *   move the questions into the parent form - TODO
				 */
				if(qType.equals("begin repeat") || qType.equals("geopolygon") || qType.equals("geolinestring")) {
				
					String tableName = null;
					
					// 1. Get the table name for this form
					String sqlGetTableName = "select table_name, parentform, repeats from form where parentquestion = ? and s_id = ?;";
					pstmtGetTableName = sd.prepareStatement(sqlGetTableName);
					pstmtGetTableName.setInt(1, q.id);
					pstmtGetTableName.setInt(2, sId);
					ResultSet rsRepeat = pstmtGetTableName.executeQuery();
					if(rsRepeat.next()) {
						tableName = rsRepeat.getString(1);
					}
					
					// 2. If the results table exists for this form then throw an exception
					if(tableName != null) {
				
						if(GeneralUtilityMethods.tableExists(cResults, tableName)) {
							throw new Exception("Cannot delete this form as it contains published data");
						} else {
							
							// 3. Delete the form
							String sqlDeleteForm = "delete from form where parentquestion = ? and s_id = ?;";
							pstmtDeleteForm = sd.prepareStatement(sqlDeleteForm);
							pstmtDeleteForm.setInt(1, q.id);
							pstmtDeleteForm.setInt(2, sId);
							
							log.info("Deleting form: " + pstmtDeleteForm.toString());
							pstmtDeleteForm.executeUpdate();
							
						}
					}
					
				}
				
				SurveyManager sm = new SurveyManager();
				sm.removeUnusedSurveyManifests(sd, sId);
				
			}
			
			
		} catch(Exception e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtUpdateSeq != null) {pstmtUpdateSeq.close();}} catch (SQLException e) {}
			try {if (pstmtDelLabels != null) {pstmtDelLabels.close();}} catch (SQLException e) {}
			try {if (pstmtDelHints != null) {pstmtDelHints.close();}} catch (SQLException e) {}
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
			try {if (pstmtGetSeq != null) {pstmtGetSeq.close();}} catch (SQLException e) {}
			try {if (pstmtSoftDelete != null) {pstmtSoftDelete.close();}} catch (SQLException e) {}
			try {if (pstmtGetTableName != null) {pstmtGetTableName.close();}} catch (SQLException e) {}
			//try {if (pstmtTableExists != null) {pstmtTableExists.close();}} catch (SQLException e) {}
			try {if (pstmtDeleteForm != null) {pstmtDeleteForm.close();}} catch (SQLException e) {}
		}	
		
	}
	
	/*
	 * Save options
	 * Called by editor
	 * Add new options
	 */
	public void saveOptions(Connection sd, int sId, ArrayList<Option> options, boolean updateLabels) throws SQLException {
		
		PreparedStatement pstmtInsertOption = null;
		String sql = "insert into option (o_id, l_id, seq, label_id, ovalue, column_name, cascade_filters, externalfile) " +
				"values (nextval('o_seq'), ?, ?, ?, ?, ?, ?, 'false');";

		PreparedStatement pstmtUpdateSeq = null;
		String sqlUpdateSeq = "update option set seq = seq + 1 where l_id = ? and seq >= ?;";
		
		try {
			pstmtUpdateSeq = sd.prepareStatement(sqlUpdateSeq);
			pstmtInsertOption = sd.prepareStatement(sql);
			
			for(Option o : options) {
				
				// Get the list id for this option
				int listId = GeneralUtilityMethods.getListId(sd, sId, o.optionList);
				Gson gson=  new GsonBuilder().disableHtmlEscaping().setDateFormat("yyyy-MM-dd").create();
				
				
				// Update sequence numbers of options after the option to be inserted
				pstmtUpdateSeq.setInt(1, listId);
				pstmtUpdateSeq.setInt(2, o.seq);
				
				log.info("Update sequences: " + pstmtUpdateSeq.toString());
				pstmtUpdateSeq.executeUpdate();
				
				String transId = "option_" + listId + "_" + o.value;
				// Insert the option
				pstmtInsertOption.setInt(1, listId );
				pstmtInsertOption.setInt(2, o.seq );
				pstmtInsertOption.setString(3, transId + ":label" );
				pstmtInsertOption.setString(4, o.value );
				pstmtInsertOption.setString(5, GeneralUtilityMethods.cleanName(o.value, false, false, false) );
				pstmtInsertOption.setString(6, gson.toJson(o.cascade_filters));			
				
				log.info("Insert option: " + pstmtInsertOption.toString());
				pstmtInsertOption.executeUpdate();
				
				// Set the labels 
				if (updateLabels && transId != null && transId.trim().length() > 0) {
					UtilityMethodsEmail.setLabels(sd, sId, transId, o.labels, "");
				}
			}
			
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtUpdateSeq != null) {pstmtUpdateSeq.close();}} catch (SQLException e) {}
			try {if (pstmtInsertOption != null) {pstmtInsertOption.close();}} catch (SQLException e) {}
		}	
		
	}
	
	/*
	 * Delete options
	 */
	public void deleteOptions(Connection sd, int sId, ArrayList<Option> options, boolean updateLabels) throws SQLException {
		
		PreparedStatement pstmtDelLabels = null;
		String sqlDelLabels = "delete from translation t where t.s_id = ? and " +
				"t.text_id in (select label_id  from option where l_id = ? and ovalue = ?); ";
		
		PreparedStatement pstmt = null;
		String sql = "delete from option " +
				" where l_id = ? " +
				" and ovalue = ? or ovalue is null;";	// Also delete any null values, these should not exist	

		PreparedStatement pstmtUpdateSeq = null;
		String sqlUpdateSeq = "update option set seq = seq - 1 where l_id = ? and seq >= ?;";
		
		try {
			pstmtUpdateSeq = sd.prepareStatement(sqlUpdateSeq);
			pstmtDelLabels = sd.prepareStatement(sqlDelLabels);
			pstmt = sd.prepareStatement(sql);
			
			for(Option o : options) {
				
				// Get the list id for this option
				int listId = GeneralUtilityMethods.getListId(sd, sId, o.optionList);
					
				// Delete the option labels
				if(updateLabels) {
					pstmtDelLabels.setInt(1, sId );
					pstmtDelLabels.setInt(2, listId );
					pstmtDelLabels.setString(3, o.value );
					
					log.info("Delete option labels: " + pstmtDelLabels.toString());
					pstmtDelLabels.executeUpdate();
				}
				
				// Delete the option
				pstmt.setInt(1, listId );
				pstmt.setString(2, o.value );
				
				log.info("Delete option: " + pstmt.toString());
				pstmt.executeUpdate();
				
				// Update sequence numbers of options after the option to be inserted
				pstmtUpdateSeq.setInt(1, listId);
				pstmtUpdateSeq.setInt(2, o.seq);
				
				log.info("Update sequences: " + pstmtUpdateSeq.toString());
				pstmtUpdateSeq.executeUpdate();
			}
			
			
		} catch(SQLException e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtUpdateSeq != null) {pstmtUpdateSeq.close();}} catch (SQLException e) {}
			try {if (pstmt != null) {pstmt.close();}} catch (SQLException e) {}
			try {if (pstmtDelLabels != null) {pstmtDelLabels.close();}} catch (SQLException e) {}
		}	
		
	}
	
	/*
	 * Move options
	 */
	public void moveOptions(Connection sd, int sId, ArrayList<Option> options) throws Exception {
		
		PreparedStatement pstmtMoveWithin = null;
		String sqlMoveWithin = "update option set "
						+ "seq = ? "
					+ "where l_id = ? "
					+ "and ovalue = ?;";
		
		PreparedStatement pstmtMovedBack = null;
		String sqlMovedBack = "update option set seq = seq + 1 where l_id = ? and seq >= ? and seq < ?;";
		
		PreparedStatement pstmtMovedForward = null;
		String sqlMovedForward = "update option set seq = seq - 1 where l_id = ? and seq > ? and seq <= ?;";
		
		PreparedStatement pstmtMovedToAnotherList = null;
		String sqlMovedToAnotherList = "update option set seq = -100, l_id = ? where l_id = ? and ovalue = ?;";
		
		try {
			pstmtMoveWithin = sd.prepareStatement(sqlMoveWithin);
			
			for(Option o : options) {
				
				boolean moveWithinList = o.optionList.equals(o.sourceOptionList);
			
				// Get the target list id for this option
				int listId = GeneralUtilityMethods.getListId(sd, sId, o.optionList);
				int sourceListId = GeneralUtilityMethods.getListId(sd, sId, o.sourceOptionList);

				if(!moveWithinList) {
					// 1. Change the list id and set the sequence to -100
					
					o.sourceSeq = -100;
					
					pstmtMovedToAnotherList = sd.prepareStatement(sqlMovedToAnotherList);
					pstmtMovedToAnotherList.setInt(1, listId);
					pstmtMovedToAnotherList.setInt(2, sourceListId);
					pstmtMovedToAnotherList.setString(3, o.value);
					
					log.info("Move to another list: " + pstmtMovedToAnotherList.toString());
					pstmtMovedToAnotherList.executeUpdate();
					
					// 2. Reorder the questions in the old form
					GeneralUtilityMethods.cleanOptionSequences(sd, sourceListId);
				}
					
				// First ensure the sequences start from 0 and increment by 1 each time which is how the editor expected them to be
				GeneralUtilityMethods.cleanOptionSequences(sd, listId);
				
				// Update sequence numbers of other options
				if(o.seq > o.sourceSeq) { // Moved forward in list
					
					pstmtMovedForward = sd.prepareStatement(sqlMovedForward);
					pstmtMovedForward.setInt(1,listId);
					pstmtMovedForward.setInt(2, o.sourceSeq);
					pstmtMovedForward.setInt(3, o.seq);
					
					log.info("Moving forward: " + pstmtMovedForward.toString());
					pstmtMovedForward.executeUpdate();
				} else {	// Moved backward in list
					
					pstmtMovedBack = sd.prepareStatement(sqlMovedBack);
					pstmtMovedBack.setInt(1,listId);
					pstmtMovedBack.setInt(2, o.seq);
					pstmtMovedBack.setInt(3, o.sourceSeq);
					
					log.info("Moving back: " + pstmtMovedBack.toString());
					pstmtMovedBack.executeUpdate();
					
				}
				
				// Move the option
				pstmtMoveWithin.setInt(1, o.seq );
				pstmtMoveWithin.setInt(2, listId );
				pstmtMoveWithin.setString(3, o.value);
				
				log.info("Move choice within same list: " + pstmtMoveWithin.toString());
				int count = pstmtMoveWithin.executeUpdate();
				if(count == 0) {
					String msg = "Warning: choice " + o.value + " in list " + o.optionList + 
							" was not moved. It may have already been moved by someone else";
					log.info(msg);
					throw new Exception(msg);		// No matching value assume it has already been modified
				}
					
			}
			
			
		} catch(SQLException e) {
			String msg = e.getMessage();
			if(msg == null || !msg.startsWith("Warning")) {
				log.log(Level.SEVERE,"Error", e);
			}
			throw e;
		} finally {
			try {if (pstmtMoveWithin != null) {pstmtMoveWithin.close();}} catch (SQLException e) {}
			try {if (pstmtMovedBack != null) {pstmtMovedBack.close();}} catch (SQLException e) {}
			try {if (pstmtMovedForward != null) {pstmtMovedForward.close();}} catch (SQLException e) {}
			try {if (pstmtMovedToAnotherList != null) {pstmtMovedToAnotherList.close();}} catch (SQLException e) {}
		}	
		
	}
	
	/*
	 * Update properties for options
	 * Use the value of the choice to identify it, however if the optionId is available then use that as well as
	 *  otherwise there can be errors if one option has its value changed to be the same as another option while that
	 *  second option also has its value modified
	 */
	public void updateOptions(Connection sd, int sId, ArrayList<PropertyChange> properties) throws Exception {
		
		PreparedStatement pstmtOtherProperties = null;

		PreparedStatement pstmtUpdateValue = null;
		String sqlUpdateValue = "update option set ovalue = ?, label_id = ? "
				+ "where l_id = ? "
				+ "and ovalue = ?";
		
		// If the option value changes then its label id needs to be updated as this is derived from the option value
		PreparedStatement pstmtGetOldLabelId = null;
		String sqlGetOldLabelId = "select label_id from option where l_id = ? and ovalue = ?";
		
		PreparedStatement pstmtUpdateLabelId = null;
		String sqlUpdateLabelId = "update translation t set text_id = ? where s_id = ? and text_id = ?";
		
		boolean hasOptionId = false;
		try {
			
			for(PropertyChange p : properties) {
				
				if(p.o_id > 0) {
					hasOptionId = true;
					sqlUpdateValue += " and o_id = ?";
					sqlGetOldLabelId += " and o_id = ?";
				}
				String property = p.prop;	
				int listId = GeneralUtilityMethods.getListId(sd, sId, p.optionList);		// Get the list id for this option
				
				if(property.equals("value")) {
					String newLabelId = "option_" + listId + "_" + p.newVal + "_" + p.o_id + ":label";
					String oldLabelId = null;
					
					// Get the old labelId
					pstmtGetOldLabelId = sd.prepareStatement(sqlGetOldLabelId);
					pstmtGetOldLabelId.setInt(1, listId);
					pstmtGetOldLabelId.setString(2, p.oldVal);
					if(hasOptionId) {
						pstmtGetOldLabelId.setInt(3, p.o_id);
					}
					
					log.info("Get old label id: " + pstmtGetOldLabelId.toString());
					ResultSet rs = pstmtGetOldLabelId.executeQuery();
					if(rs.next()) {
						oldLabelId = rs.getString(1);
					} 
					
					pstmtUpdateValue = sd.prepareStatement(sqlUpdateValue);
					pstmtUpdateValue.setString(1, p.newVal);
					pstmtUpdateValue.setString(2, newLabelId);
					pstmtUpdateValue.setInt(3, listId);
					pstmtUpdateValue.setString(4, p.oldVal);
					if(hasOptionId) {
						pstmtUpdateValue.setInt(5, p.o_id);
					}
					
					log.info("Update option value: " + pstmtUpdateValue.toString());
					pstmtUpdateValue.executeUpdate();
					

					// Update the label id
					pstmtUpdateLabelId = sd.prepareStatement(sqlUpdateLabelId);
					pstmtUpdateLabelId.setString(1, newLabelId);
					pstmtUpdateLabelId.setInt(2, sId);
					pstmtUpdateLabelId.setString(3, oldLabelId);
					
					log.info("Update option label id: " + pstmtUpdateLabelId.toString());
					pstmtUpdateLabelId.executeUpdate();
					
				} else {
					if(GeneralUtilityMethods.columnType(sd, "option", property) != null) {
					
						String sql = "update option set  " + property + " = ? "
								+ "where l_id = ? "
								+ "and ovalue = ?";
						
						pstmtOtherProperties = sd.prepareStatement(sql);
							
						// Update the option
						pstmtOtherProperties.setString(1, p.newVal );
						pstmtOtherProperties.setInt(2, listId );
						pstmtOtherProperties.setString(3, p.name );
						
						log.info("Update option: " + pstmtOtherProperties.toString());
						pstmtOtherProperties.executeUpdate();
							
						
					}
				}
			}
			
			
		} catch(Exception e) {
			log.log(Level.SEVERE,"Error", e);
			throw e;
		} finally {
			try {if (pstmtOtherProperties != null) {pstmtOtherProperties.close();}} catch (SQLException e) {}
			try {if (pstmtUpdateValue != null) {pstmtUpdateValue.close();}} catch (SQLException e) {}
			try {if (pstmtUpdateLabelId != null) {pstmtUpdateLabelId.close();}} catch (SQLException e) {}
			try {if (pstmtGetOldLabelId != null) {pstmtGetOldLabelId.close();}} catch (SQLException e) {}
		}	
		
	}
	
	/*
	 * Get a changeset with option updates for a question from a CSV file
	 */
	public ChangeSet getCSVChangeSetForQuestion(Connection sd, 
			File csvFile,
			String csvFileName,
			org.smap.sdal.model.Question q) {
		
		ChangeSet cs = new ChangeSet();
		
		cs.changeType = "option";
		cs.source = "file";
		cs.items = new ArrayList<ChangeItem> ();
		
		GeneralUtilityMethods.getOptionsFromFile(
				sd,
				cs.items,
				csvFile,
				csvFileName,
				q.name,
				q.l_id,
				q.id,
				q.type,
				q.appearance);
		
		return cs;
	}
	
	/*
	 * Duplicate a form
	 */
	public void duplicateForm(Connection sd, 
			int sId, 					// New survey id
			int existingSurveyId,
			String formName, 
			int originalFormId, 
			String parentPath,
			int parentFormId,
			int parentQuestionId,
			boolean sharedResults,
			String formLabel,
			String repeats) throws Exception {
		
		String tablename = null;
		int fId;									// Id of the newly created form
		//String path = parentPath + "/" + formName;
		
		String sqlGetTableName = "select table_name from form where f_id = ?;";
		PreparedStatement pstmtGetTableName = sd.prepareStatement(sqlGetTableName);
		
		String sqlCreateForm = "insert into form ( f_id, s_id, name, label, table_name, parentform, "
				+ "parentquestion, path, repeats) " +
				" values (nextval('f_seq'), ?, ?, ?, ?, ?, ?, ?, ?);";
		PreparedStatement pstmtCreateForm = sd.prepareStatement(sqlCreateForm, Statement.RETURN_GENERATED_KEYS);
		
		String sqlGetSubForms = "select f.f_id, f.name, f.parentquestion, f.label, f.repeats from form f "
				+ "where f.parentform = ?;";
		PreparedStatement pstmtGetSubForms = sd.prepareStatement(sqlGetSubForms);
		
		String sqlGetParentQuestionId = "select q_id from question where f_id = ? "
				+ "and qname = (select qname from question where q_id = ?);";
		PreparedStatement pstmtGetParentQuestionId = sd.prepareStatement(sqlGetParentQuestionId);
		
		try{
			
			if(sharedResults) {
				pstmtGetTableName.setInt(1, originalFormId);
				
				log.info("Get table name: " + pstmtGetTableName.toString());
				ResultSet rsTableName = pstmtGetTableName.executeQuery();
				if(rsTableName.next()) {
					tablename = rsTableName.getString(1);
				} else {
					throw new Exception("Could not get table name of existing form");
				}
			} else {
				tablename = "s" + sId + "_" + GeneralUtilityMethods.cleanName(formName, true, false, false);
			}
			
			pstmtCreateForm.setInt(1,  sId);
			pstmtCreateForm.setString(2, formName);
			pstmtCreateForm.setString(3, formLabel);
			pstmtCreateForm.setString(4,  tablename);
			pstmtCreateForm.setInt(5,  parentFormId);
			pstmtCreateForm.setInt(6,  parentQuestionId);
			pstmtCreateForm.setString(7,  "");	// path is no longer used
			pstmtCreateForm.setString(8,  repeats);
		
			log.info("Create new form: " + pstmtCreateForm.toString());
			pstmtCreateForm.execute();
		
			ResultSet rs = pstmtCreateForm.getGeneratedKeys();
			rs.next();
			fId = rs.getInt(1);
			
			duplicateQuestions(sd, originalFormId, sId, fId, sharedResults);
			duplicateQuestionLabels(sd, sId, existingSurveyId, originalFormId);
			duplicateOptionsInForm(sd, sId, fId, existingSurveyId);
			
			// Duplicate sub forms
			pstmtGetSubForms.setInt(1,originalFormId);
			
			log.info("Get sub forms: " + pstmtGetSubForms.toString());
			rs = pstmtGetSubForms.executeQuery();
			String subFormParentPath = parentPath + "/" + formName;
			while(rs.next()) {
				int subFormId = rs.getInt(1);
				String subFormName = rs.getString(2);
				int existingParentQuestionId = rs.getInt(3);
				String existingFormLabel = rs.getString(4);
				String existingRepeats = rs.getString(5);
				
				// Get the parent question id
				pstmtGetParentQuestionId.setInt(1, fId);
				pstmtGetParentQuestionId.setInt(2, existingParentQuestionId);
				
				log.info("Get existing parent question id: " + pstmtGetParentQuestionId.toString());
				ResultSet rsParent = pstmtGetParentQuestionId.executeQuery();
				int newParentQuestionId = 0;
				if(rsParent.next()) {
					newParentQuestionId = rsParent.getInt(1);
					duplicateForm(sd, sId, existingSurveyId, subFormName, subFormId, subFormParentPath, fId, 
							newParentQuestionId, sharedResults, existingFormLabel, existingRepeats);
				}
				
				
			}
			
		} catch(Exception e) {
			throw e;
		} finally {
			if(pstmtCreateForm != null) try {pstmtCreateForm.close();} catch(Exception e){};
			if(pstmtGetSubForms != null) try {pstmtGetSubForms.close();} catch(Exception e){};
			if(pstmtGetParentQuestionId != null) try {pstmtGetParentQuestionId.close();} catch(Exception e){};
		}
	}
	
	/*
	 * Duplicate the languages in the survey
	 */
	public void duplicateLanguages(Connection sd, 
			int sId,					// The new survey
			int existingSurveyId) throws Exception {
		
		String sql = "insert into language ("
				+ "s_id,"
				+ "seq,"
				+ "language) "
				+ "select "
				+ sId + ","
				+ "seq,"
				+ "language "
				+ "from language where s_id = ?;";
		
	PreparedStatement pstmt = null;
		
		try {
			
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, existingSurveyId);
			
			log.info("Duplicating languages: " + pstmt.toString());
			pstmt.executeUpdate();
			
		} catch (Exception e) {
			throw e;
		} finally {
			if(pstmt != null) try {pstmt.close();} catch(Exception e){};
		}

	}
	
	/*
	 * Copy translations in a form to a new form
	 */
	public void duplicateQuestionLabels(Connection sd, 
			int sId,					// The new survey
			int existingSurveyId,       // The existing survey
			int existingFormId) throws Exception {
		
		String sql = "insert into translation("
				+ "s_id,"
				+ "language,"
				+ "text_id,"
				+ "type,"
				+ "value) "
				+ "select "
				+ sId + ","
				+ "language,"
				+ "text_id,"
				+ "type,"
				+ "value "
				+ "from translation "
				+ "where s_id = ? "
				+ "and "
				+ "(text_id in (select qtext_id from question where f_id = ?) "
				+ "or text_id in (select infotext_id from question where f_id = ?));";
		PreparedStatement pstmt = null;
		
		try {
			
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, existingSurveyId);
			pstmt.setInt(2, existingFormId);
			pstmt.setInt(3, existingFormId);
			
			log.info("Duplicating question labels: " + pstmt.toString());
			pstmt.executeUpdate();
			
		} catch (Exception e) {
			throw e;
		} finally {
			if(pstmt != null) try {pstmt.close();} catch(Exception e){};
		}

	}
	
	/*
	 * Copy translations in an option list to a new list
	 */
	public void duplicateOptionLabels(Connection sd, 
			int sId,
			int existingSurveyId,       // The existing survey
			int listId				  // The new list
			) throws Exception {
		
		String sql = "insert into translation ("
				+ "s_id,"
				+ "language,"
				+ "text_id,"
				+ "type,"
				+ "value) "
				+ "select "
				+ sId + ","
				+ "language,"
				+ "text_id,"
				+ "type,"
				+ "value "
				+ "from translation "
				+ "where s_id = ? "
				+ "and "
				+ "text_id in (select label_id from option where l_id = ?); ";
		PreparedStatement pstmt = null;
		
		Savepoint optionLabelsSP = sd.setSavepoint();
		try {
			
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, existingSurveyId);
			pstmt.setInt(2, listId);
			
			log.info("Duplicating option labels: " + pstmt.toString());
			pstmt.executeUpdate();
			
		} catch (Exception e) {
			if(e.getMessage().contains("duplicate key value violates unique constraint")) {
				log.info("Warning:  Option labels already exist");
				sd.rollback(optionLabelsSP);
			} else {
				throw e;
			}
		} finally {
			if(pstmt != null) try {pstmt.close();} catch(Exception e){};
			sd.releaseSavepoint(optionLabelsSP);
		}

	}
	
	/*
	 * Copy questions in a form to a new form
	 */
	public void duplicateQuestions(Connection sd, int existingFormId, int sId, int newFormId, boolean sharedResults) throws Exception {
		
		String sql = "insert into question("
				 + "q_id,"
				 + "f_id,"
				 + "seq,"
				 + "qname,"
				 + "qtype," 
				 + "question,"
				 + "qtext_id,"			// May need to replicate translations
				 + "defaultanswer,"
				 + "info,"
				 + "infotext_id, "		// May need to replicate translations 
				 + "visible, " 
				 + "source, "
				 + "source_param, "
				 + "readonly, "
				 + "mandatory, "
				 + "relevant, "
				 + "calculate, "
				 + "qconstraint, "
				 + "constraint_msg,"
				 + "required_msg,"
				 + "appearance,"
				 + "enabled,"
				 + "path,"
				 + "nodeset,"
				 + "nodeset_value,"
				 + "nodeset_label,"
				 + "cascade_instance,"
				 + "column_name," 
				 + "published,"
				 + "column_name_applied,"
				 + "l_id)"						// List ids will need to be updated
				 
				 // Get the existing data
				 + " select "
				 + "nextval('q_seq'), "
				 + newFormId + ","				// Set the new form id
				 + "seq,"
				 + "qname,"
				 + "qtype," 
				 + "question,"
				 + "qtext_id,"			
				 + "defaultanswer,"
				 + "info,"
				 + "infotext_id, "		
				 + "visible, " 
				 + "source, "
				 + "source_param, "
				 + "readonly, "
				 + "mandatory, "
				 + "relevant, "
				 + "calculate, "
				 + "qconstraint, "
				 + "constraint_msg, "
				 + "required_msg, "
				 + "appearance, "
				 + "enabled, "
				 + "path, "
				 + "nodeset, "
				 + "nodeset_value, "
				 + "nodeset_label, "
				 + "cascade_instance, "
				 + "column_name," 
				 + (sharedResults ? "published, " : "'false', ")	// Set to false if this question is for a new table	
				 + "column_name_applied, "
				 + "l_id "
				 
				 + "from question where f_id = ? "		// Existing form id
				 + "and soft_deleted = 'false';";	
		PreparedStatement pstmt = null;
		
		String sqlGetQuestionsForManifest = "select appearance, calculate from question "
				+ "where f_id = ? "
				+ "and (appearance is not null or calculate is not null);";
		PreparedStatement pstmtGetQuestionsForManifest = null;
		
		try {
			
			pstmt = sd.prepareStatement(sql);
			pstmt.setInt(1, existingFormId);
			
			log.info("Duplicating questions: " + pstmt.toString());
			pstmt.executeUpdate();
			
			// Update the survey manifest if this question references CSV files
			SurveyManager sm = new SurveyManager();	
			pstmtGetQuestionsForManifest = sd.prepareStatement(sqlGetQuestionsForManifest);
			pstmtGetQuestionsForManifest.setInt(1, existingFormId);
			log.info("Getting questions that may affect manifest: " + pstmtGetQuestionsForManifest.toString());
			
			ResultSet rs = pstmtGetQuestionsForManifest.executeQuery();
			while(rs.next()) {
				sm.updateSurveyManifest(sd, sId, rs.getString("appearance"), rs.getString("calculate"));
			}
			
			
		} catch (Exception e) {
			throw e;
		} finally {
			if(pstmt != null) try {pstmt.close();} catch(Exception e){};
			if(pstmtGetQuestionsForManifest != null) try {pstmtGetQuestionsForManifest.close();} catch(Exception e){};
		}

	}
	
	/*
	 * Copy options from one form to a new form
	 */
	public void duplicateOptionsInForm(Connection sd, 
			int sId,					// The new surveyId
			int fId,					// The form id
			int existingSurveyId
			) throws Exception {
		
		HashMap<Integer, Integer> listIdHash = new HashMap<Integer, Integer> ();
		int newListId;
		
		String sqlGetLists = "select q.l_id from question q, form f "
				+ "where f.f_id = q.f_id "
				+ "and q.l_id > 0 "
				+ "and q.qtype like 'select%' "
				+ "and f.f_id = ? ;";
		PreparedStatement pstmtGetLists = null;
		
		String sqlGetListName = "select name from listname where l_id = ?;";
		PreparedStatement pstmtGetListName = sd.prepareStatement(sqlGetListName);
		
		String sqlCheckListName = "select count(*) from listname where s_id = ? and name = ?;";
		PreparedStatement pstmtCheckListName = sd.prepareStatement(sqlCheckListName);
		
		String sqlCreateList = "insert into listname ( s_id, name) " +
				" values (?, ?); ";
		PreparedStatement pstmtCreateList = sd.prepareStatement(sqlCreateList, Statement.RETURN_GENERATED_KEYS);;
		
		PreparedStatement pstmtInsertOptions = null;
		
		String sqlUpdateListId = "update question set l_id = ? where f_id = ? and l_id = ?;";
		PreparedStatement pstmtUpdateListId = sd.prepareStatement(sqlUpdateListId);
		
		try {
			
			// Get the lists that need to be duplicated for this form
			pstmtGetLists = sd.prepareStatement(sqlGetLists);
			pstmtGetLists.setInt(1, fId);	
		
			log.info("Getting option lists that need to be replicated: " + pstmtGetLists.toString());
			ResultSet rs = pstmtGetLists.executeQuery();
			while(rs.next()) {
				int l_id = rs.getInt(1);
				listIdHash.put(new Integer(l_id), new Integer(l_id));
			}
			
			List<Integer> listIds = new ArrayList<Integer>(listIdHash.keySet());
        	for(Integer listId : listIds) {
        		
        		// 1. Get the list name
        		pstmtGetListName.setInt(1, listId);
        		
        		log.info("Getting list name: " + pstmtGetListName.toString());
        		rs = pstmtGetListName.executeQuery();
        		rs.next();
        		String listName = rs.getString(1);
        		
        		// 2. Ignore if this list has already been added
        		pstmtCheckListName.setInt(1, sId);
        		pstmtCheckListName.setString(2, listName);
        		
        		log.info("Checking list name: " + pstmtCheckListName.toString());
        		rs = pstmtCheckListName.executeQuery();
        		rs.next();
        		if(rs.getInt(1) > 0) {
        			log.info("List name " + listName + " has already been added");
        			continue;
        		}
        		
        		// 3. Create a new list  		
    			pstmtCreateList.setInt(1,  sId);
    			pstmtCreateList.setString(2,  listName);
   
    			log.info("Create new list: " + pstmtCreateList.toString());
    			pstmtCreateList.execute();
    		
    			rs = pstmtCreateList.getGeneratedKeys();
    			rs.next();
    			newListId = rs.getInt(1);
    			
        		// 4. Create the list entries
    			String sqlInsertOptions = "insert into option ("
    					 + "o_id,"
    					 + "l_id,"
    					 + "seq,"
    					 + "label," 
    					 + "label_id,"
    					 + "ovalue,"
    					 + "cascade_filters," 
    					 + "externalfile,"
    					 + "column_name,"
    					 + "published) "
    					 
    					 + "select "
    					 + "nextval('o_seq'), "
    					 + newListId + ","
       					 + "seq,"
    					 + "label," 
    					 + "label_id,"
    					 + "ovalue,"
    					 + "cascade_filters," 
    					 + "externalfile,"
    					 + "column_name,"
    					 + "'false'"
    					 + "from option where l_id = ?;";
    			
    			if(pstmtInsertOptions != null) try {pstmtInsertOptions.close();} catch(Exception e){};
    			pstmtInsertOptions = sd.prepareStatement(sqlInsertOptions);
    			pstmtInsertOptions.setInt(1, listId);
    			
    			log.info("Adding options to survey: " + pstmtInsertOptions.toString());
    			pstmtInsertOptions.executeUpdate();
        		
    			// 5. Update the list id in the form to point to the newly created list
    			pstmtUpdateListId.setInt(1, newListId);
    			pstmtUpdateListId.setInt(2, fId);
    			pstmtUpdateListId.setInt(3, listId);
    			
    			log.info("Update list id in new form: " + pstmtUpdateListId.toString());
    			pstmtUpdateListId.executeUpdate();
    			
        		// 6. Copy the list labels
    			duplicateOptionLabels(sd, sId, existingSurveyId, newListId);
        	}

			
		} catch (Exception e) {
			throw e;
		} finally {
			if(pstmtGetLists != null) try {pstmtGetLists.close();} catch(Exception e){};
			if(pstmtCreateList != null) try {pstmtCreateList.close();} catch(Exception e){};
			if(pstmtInsertOptions != null) try {pstmtInsertOptions.close();} catch(Exception e){};
			if(pstmtGetListName != null) try {pstmtGetListName.close();} catch(Exception e){};
			if(pstmtCheckListName != null) try {pstmtCheckListName.close();} catch(Exception e){};
			if(pstmtUpdateListId != null) try {pstmtUpdateListId.close();} catch(Exception e){};
		}

	}

}
