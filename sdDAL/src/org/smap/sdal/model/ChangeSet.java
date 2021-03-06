package org.smap.sdal.model;

import java.util.ArrayList;

/*
 * Contains set of changes that need to be applied in a single transaction
 */
public class ChangeSet {
	public String changeType;				// Type of change: label, option_update, Survey, form, language, question, option
	public String type;						// question | option
	public String action;					// add | update | delete
	public String source;					// editor (default) | file
	public ArrayList<ChangeItem> items;		// Set of changes of the above type
	
	// Response data 
	public boolean updateFailed = false;
	public String errorMsg;

}
