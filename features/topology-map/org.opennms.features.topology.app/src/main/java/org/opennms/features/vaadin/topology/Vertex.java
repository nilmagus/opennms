package org.opennms.features.vaadin.topology;

import com.google.gwt.user.client.Window;
import com.vaadin.terminal.gwt.client.ApplicationConnection;
import com.vaadin.terminal.gwt.client.Paintable;
import com.vaadin.terminal.gwt.client.UIDL;
import com.vaadin.data.Item;

public class Vertex implements Paintable {

	private String m_key;
	private Object m_itemId;
	private Item m_item;
	private Object m_groupId;
	private String m_groupKey;
	
	private boolean m_selected = false;
    
	public Vertex(String key, Object itemId, Item item, String groupKey, Object groupId) {
		m_key = key;
		m_itemId = itemId;
		m_item = item;
		m_groupKey = groupKey;
		m_groupId = groupId;
	}
	
	public Object getItemId() {
		return m_itemId;
	}
	
	public String getGroupKey() {
		return m_groupKey;
	}
	
	public Object getGroupId() {
		return m_groupId;
	}
	
	public boolean isLeaf() {
		return (Boolean) m_item.getItemProperty("leaf").getValue();
	}
	
	public int getX() {
		return (Integer) m_item.getItemProperty("x").getValue();
		
	};
	
	public int getY(){
		return (Integer) m_item.getItemProperty("y").getValue();
	}
	
	public void setX(int x) {
		m_item.getItemProperty("x").setValue(x);
    }

    public void setY(int y) {
    	m_item.getItemProperty("y").setValue(y);
    }
    
    public String toString() {
    	return "v" + getItemId() + "(" + getX()  + "," + getY() + "):" + (m_selected ? "selected" : "unselected");
    }

    public void updateFromUIDL(UIDL uidl, ApplicationConnection client) {
        Window.alert("getting Update from Server");
    }

	public void setSelected(boolean selected) {
		m_item.getItemProperty("selected").setValue(selected);
	}
	
	public boolean isSelected() {
		return (Boolean) m_item.getItemProperty("selected").getValue();
	}

	public Object getItem() {
		return m_item;
	}
    
    public String getIconUrl() {
        return (String) m_item.getItemProperty("icon").getValue();
    }

	public String getKey() {
		return m_key;
	}
	
	public int getSemanticZoomLevel() {
		return (Integer) m_item.getItemProperty("semanticZoomLevel").getValue();
	}

	public void setGroupId(Object groupId) {
		m_groupId = groupId;
	}

	public void setGroupKey(String groupKey) {
		m_groupKey = groupKey;
	}
	
	public boolean isLocked() {
		return (Boolean) m_item.getItemProperty("locked").getValue();
	}
	
	public void setLocked(boolean locked) {
		m_item.getItemProperty("locked").setValue(locked);
	}

}