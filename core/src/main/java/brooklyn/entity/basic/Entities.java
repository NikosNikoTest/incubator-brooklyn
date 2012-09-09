package brooklyn.entity.basic;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.ConfigKey;
import brooklyn.entity.Effector;
import brooklyn.entity.Entity;
import brooklyn.entity.trait.Startable;
import brooklyn.event.AttributeSensor;
import brooklyn.event.Sensor;
import brooklyn.location.Location;
import brooklyn.management.ManagementContext;
import brooklyn.management.Task;
import brooklyn.util.MutableMap;
import brooklyn.util.ResourceUtils;
import brooklyn.util.task.ParallelTask;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;


/** Convenience methods for working with entities. 
 * Also see the various *Methods classes for traits 
 * (eg StartableMethods for Startable implementations). */
public class Entities {

    private static final Logger log = LoggerFactory.getLogger(Entities.class);
    
	/** invokes the given effector with the given named arguments on the entitiesToCall, from the calling context of the callingEntity;
	 * intended for use only from the callingEntity
	 * @return ParallelTask containing a results from each invocation; calling get() on the result will block until all complete,
	 * and throw error if any threw error   
	 */
	public static <T> Task<List<T>> invokeEffectorList(EntityLocal callingEntity, Iterable<Entity> entitiesToCall, 
	        final Effector<T> effector, final Map<String,?> parameters) {
        // formulation is complicated, but it is building up a list of tasks, without blocking on them initially,
        // but ensuring that when the parallel task is gotten it does block on all of them
	    // TODO why not just get list of tasks with `entity.invoke(effector, parameters))`?
	    //      What is advantage of invoking in callingEntity's context?
        
		if (entitiesToCall == null || Iterables.isEmpty(entitiesToCall)) return null;
		List<Callable<T>> tasks = Lists.newArrayList();
		
		for (final Entity entity : entitiesToCall) {
		    tasks.add(new Callable<T>() {
		        public T call() throws Exception {
		            return entity.invoke(effector, parameters).get();
		        }});
		}
	    ParallelTask<T> invoke = new ParallelTask<T>(tasks);
	    callingEntity.getExecutionContext().submit(invoke);
	    return invoke;
	}
    public static <T> Task<List<T>> invokeEffectorList(EntityLocal callingEntity, Iterable<Entity> entitiesToCall, 
            final Effector<T> effector) {
        return invokeEffectorList(callingEntity, entitiesToCall, effector, Collections.<String,Object>emptyMap());
    }

    public static boolean isSecret(String name) {
        return name.contains("password") || name.contains("credential") || name.contains("secret") || name.contains("private");
    }

    public static boolean isTrivial(Object v) {
        return v==null || (v instanceof Map && ((Map<?,?>)v).isEmpty()) ||
                (v instanceof Collection && ((Collection<?>)v).isEmpty()) ||
                (v instanceof CharSequence&& ((CharSequence)v).length() == 0);
    }
    
    public static Map<Object,Object> sanitize(Map<?,?> input) {
        Map<Object,Object> result = new LinkedHashMap<Object,Object>();
        for (Map.Entry<?,?> e: input.entrySet()) {
            if (isSecret(""+e.getKey())) result.put(e.getKey(), "xxxxxxxx");
            else result.put(e.getKey(), e.getValue());
        }
        return result;
    }
    
    public static void dumpInfo(Entity e) {
        try {
            dumpInfo(e, new PrintWriter(System.out), "", "  ");
        } catch (IOException exc) {
            // system.out throwing an exception is odd, so don't have IOException on signature
            throw new RuntimeException(exc);
        }
    }
    public static void dumpInfo(Entity e, Writer out) throws IOException {
        dumpInfo(e, out, "", "  ");
    }
    public static void dumpInfo(Entity e, String currentIndentation, String tab) throws IOException {
        dumpInfo(e, new PrintWriter(System.out), currentIndentation, tab);
    }
	public static void dumpInfo(Entity e, Writer out, String currentIndentation, String tab) throws IOException {
		out.append(currentIndentation+e.toString()+"\n");
		
        out.append(currentIndentation+tab+tab+"locations = "+e.getLocations());

		for (ConfigKey<?> it : sortConfigKeys(e.getEntityType().getConfigKeys())) {
            Object v = e.getConfig(it);
            if (!isTrivial(v)) {
                out.append(currentIndentation+tab+tab+it.getName());
                out.append(" = ");
                if (isSecret(it.getName())) out.append("xxxxxxxx");
                else if ((v instanceof Task) && ((Task<?>)v).isDone()) {
                    if (((Task<?>)v).isError()) {
                        out.append("ERROR in "+v);
                    } else {
                        try {
                            out.append(((Task<?>)v).get() + " (from "+v+")");
                        } catch (ExecutionException ee) {
                            throw new IllegalStateException("task "+v+" done and !isError, but threw exception on get", ee);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } else out.append(""+v);
                out.append("\n");
            }
		}
		for (Sensor<?> it : sortSensors(e.getEntityType().getSensors())) {
			if (it instanceof AttributeSensor) {
                Object v = e.getAttribute((AttributeSensor<?>)it);
                if (!isTrivial(v)) {
                    out.append(currentIndentation+tab+tab+it.getName());
                    out.append(": ");
                    if (isSecret(it.getName())) out.append("xxxxxxxx");
                    else out.append(""+v);
                    out.append("\n");
                }
			}
		}
		for (Entity it : e.getOwnedChildren()) {
			dumpInfo(it, out, currentIndentation+tab, tab);
		}
		out.flush();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
    public static List<Sensor<?>> sortSensors(Set<Sensor<?>> sensors) {
	    List result = new ArrayList(sensors);
	    Collections.sort(result, new Comparator<Sensor>() {
                    @Override
                    public int compare(Sensor arg0, Sensor arg1) {
                        return arg0.getName().compareTo(arg1.getName());
                    }
	        
	    });
	    return result;
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static List<ConfigKey<?>> sortConfigKeys(Set<ConfigKey<?>> configs) {
        List result = new ArrayList(configs);
        Collections.sort(result, new Comparator<ConfigKey>() {
                    @Override
                    public int compare(ConfigKey arg0, ConfigKey arg1) {
                        return arg0.getName().compareTo(arg1.getName());
                    }
            
        });
        return result;
    }
    
    public static boolean isAncestor(Entity descendant, Entity potentialAncestor) {
		Entity ancestor = descendant.getOwner();
		while (ancestor != null) {
			if (ancestor.equals(potentialAncestor)) return true;
			ancestor = ancestor.getOwner();
		}
		return false;
	}

	/** note, it is usually preferred to use isAncestor() and swap the order, it is a cheaper method */
	public static boolean isDescendant(Entity ancestor, Entity potentialDescendant) {
		Set<Entity> inspected = Sets.newLinkedHashSet();
		Stack<Entity> toinspect = new Stack<Entity>();
		toinspect.add(ancestor);
		
		while (!toinspect.isEmpty()) {
			Entity e = toinspect.pop();
			if (e.getOwnedChildren().contains(potentialDescendant)) {
				return true;
			}
			inspected.add(e);
			toinspect.addAll(e.getOwnedChildren());
			toinspect.removeAll(inspected);
		}
		
		return false;
	}

    /** Interim method for assisting with entity lifecycle */
    public static Entity start(ManagementContext context, Entity e, Collection<Location> locations) {
        if (context != null) context.manage(e);
        if (e instanceof Startable) ((Startable)e).start(locations);
        return e;
    }

    /** Interim method for assisting with entity lifecycle */
    public static void destroy(ManagementContext context, Entity e) {
        if (e instanceof Startable) ((Startable)e).stop();
        if (e instanceof AbstractEntity) ((AbstractEntity)e).destroy();
        if (context != null) context.unmanage(e);
    }

    private static List<Entity> entitiesToStopOnShutdown = null;
    public static void invokeStopOnShutdown(Entity entity) {
        synchronized (Entities.class) {
            if (entitiesToStopOnShutdown==null) {
                entitiesToStopOnShutdown = new ArrayList<Entity>();
                ResourceUtils.addShutdownHook(new Runnable() {
                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    public void run() {
                        synchronized (entitiesToStopOnShutdown) {
                            log.info("Brooklyn stopOnShutdown shutdown-hook invoked: stopping "+entitiesToStopOnShutdown);
                            List<Task> stops = new ArrayList<Task>();
                            for (Entity entity: entitiesToStopOnShutdown) {
                                try {
                                    stops.add(entity.invoke(Startable.STOP, new MutableMap()));
                                } catch (Exception exc) {
                                    log.debug("stopOnShutdown of "+entity+" returned error: "+exc, exc);
                                }
                            }
                            for (Task t: stops) { 
                                try {
                                    log.debug("stopOnShutdown of {} completed: {}", t, t.get()); 
                                } catch (Exception exc) {
                                    log.debug("stopOnShutdown of "+t+" returned error: "+exc, exc);
                                }
                            }
                        }
                    }
                });
            }
        }
        synchronized (entitiesToStopOnShutdown) {
            entitiesToStopOnShutdown.add(entity);
        }
    }
    
}
