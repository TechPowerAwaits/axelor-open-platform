package com.axelor.meta.schema.actions;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

import com.axelor.db.Model;
import com.axelor.db.mapper.Mapper;
import com.axelor.meta.ActionHandler;
import com.axelor.meta.MetaStore;
import com.axelor.rpc.Response;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

@XmlType
public class ActionGroup extends Action {

	@XmlElement(name = "action")
	private List<ActionItem> actions;
	
	public List<ActionItem> getActions() {
		return actions;
	}
	
	public void setActions(List<ActionItem> actions) {
		this.actions = actions;
	}
	
	public void addAction(String name) {
		if (this.actions == null) {
			this.actions = Lists.newArrayList();
		}
		ActionItem item = new ActionItem();
		item.setName(name);
		this.actions.add(item);
	}
	
	private String getPending(Iterator<ActionItem> actions) {
		List<String> pending = Lists.newArrayList();
    	while(actions.hasNext()) {
    		pending.add(actions.next().getName());
    	}
    	return Joiner.on(",").join(pending);
	}
	
	private Action findAction(String name) {

		if (name == null || "".equals(name.trim())) {
			return null;
		}

		String actionName = name.trim();

		if (actionName.contains(":")) {

			Action action;
			String[] parts = name.split("\\:", 2);

			if (parts[0].matches("grid|form|tree|portal|calendar|chart|search|html")) {
				ActionView.View view = new ActionView.View();
				view.setType(parts[0]);
				view.setName(parts[1]);
				action = new ActionView();
				((ActionView) action).setViews(ImmutableList.of(view));
			} else {
				ActionMethod.Call method = new ActionMethod.Call();
				method.setController(parts[0]);
				method.setMethod(parts[1]);
				action = new ActionMethod();
				((ActionMethod) action).setCall(method);
			}
			return action;
		}

		return MetaStore.getAction(actionName);
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public Object evaluate(ActionHandler handler) {

		List<Object> result = Lists.newArrayList();
		Iterator<ActionItem> iter = actions.iterator();
		
		if (getName() != null) {
			log.debug("action-group: {}", getName());
		}
		
		while(iter.hasNext()) {
			Element element = iter.next();
			String name = element.getName().trim();
			
			if ("save".equals(name)) {
				if (element.test(handler)) {
					String pending = this.getPending(iter);
	            	log.debug("wait for 'save', pending actions: {}", pending);
					result.add(ImmutableMap.of("save", true, "pending", pending));
				}
				log.debug("action '{}' doesn't meet the condition: {}", "save", element.getCondition());
				break;
			}
			
			log.debug("action: {}", name);

			Action action = this.findAction(name);
			if (action == null) {
				log.error("action doesn't exist: {}", name);
                continue;
			}

			if (!element.test(handler)) {
				log.debug("action '{}' doesn't meet the condition: {}", element.getName(), element.getCondition());
				continue;
			}
			
			Object value = action.wrap(handler);
            if (value instanceof Response) {
            	Response res = (Response) value;
            	// if error or this is the only action then return the response
            	if (res.getStatus() != Response.STATUS_SUCCESS || actions.size() == 1) {
            		return res;
            	}
            	value = res.getItem(0);
            }
            if (value == null) {
            	continue;
            }
            
            // update the context if required
            if (value instanceof Map && ((Map) value).get("values") != null) {
            	Object values = ((Map) value).get("values");
            	if (values instanceof Model) {
            		values = Mapper.toMap(values);
            	}
            	if (values instanceof Map) {
            		handler.getContext().update((Map) values);
            	}
            }

            // stop for reload
            if (value instanceof Map && Objects.equal(Boolean.TRUE, ((Map) value).get("reload"))) {
            	String pending = this.getPending(iter);
            	log.debug("wait for 'reload', pending actions: {}", pending);
				((Map<String, Object>) value).put("pending", pending);
				result.add(value);
            } else if (action instanceof ActionGroup && value instanceof Collection) {
            	result.addAll((Collection<?>) value);
            } else {
            	result.add(value);
            }
            
            log.debug("action complete: {}", name);

            if (action instanceof ActionValidate && iter.hasNext()) {
            	String pending = this.getPending(iter);
            	log.debug("wait for validation: {}, {}", name, value);
            	log.debug("pending actions: {}", pending);
				((Map<String, Object>) value).put("pending", pending);
                break;
            }
		}
		return result;
	}

	@Override
	public Object wrap(ActionHandler handler) {
		return evaluate(handler);
	}

	public static class ActionItem extends Element {
		
	}

}
