package org.deri.cqels.engine;

import com.espertech.esper.client.EPStatement;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.sparql.algebra.Op;
import com.hp.hpl.jena.sparql.algebra.OpVars;
import com.hp.hpl.jena.sparql.algebra.op.OpDistinct;
import com.hp.hpl.jena.sparql.algebra.op.OpExtend;
import com.hp.hpl.jena.sparql.algebra.op.OpFilter;
import com.hp.hpl.jena.sparql.algebra.op.OpJoin;
import com.hp.hpl.jena.sparql.algebra.op.OpProject;
import com.hp.hpl.jena.sparql.core.Quad;
import com.hp.hpl.jena.sparql.core.TriplePath;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.expr.Expr;
import com.hp.hpl.jena.sparql.expr.ExprAggregator;
import com.hp.hpl.jena.sparql.syntax.Element;
import com.hp.hpl.jena.sparql.syntax.ElementBind;
import com.hp.hpl.jena.sparql.syntax.ElementFilter;
import com.hp.hpl.jena.sparql.syntax.ElementGroup;
import com.hp.hpl.jena.sparql.syntax.ElementNamedGraph;
import com.hp.hpl.jena.sparql.syntax.ElementPathBlock;
import com.hp.hpl.jena.sparql.syntax.ElementTriplesBlock;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.apache.jena.atlas.lib.SetUtils;
import org.deri.cqels.data.Mapping;
import org.deri.cqels.lang.cqels.ElementStreamGraph;
import org.deri.cqels.lang.cqels.OpStream;

/**
 * This class uses heuristic approach to build an execution plan
 *
 * @author	Danh Le Phuoc
 * @author Chan Le Van
 * @organization DERI Galway, NUIG, Ireland www.deri.ie
 * @email danh.lephuoc@deri.org
 * @email chan.levan@deri.org
 */
public class HeuristicRoutingPolicy extends RoutingPolicyBase {

    public HeuristicRoutingPolicy(ExecContext context) {
        super(context);
        this.compiler = new LogicCompiler();
        this.compiler.set(this);
    }

    /**
     * Creating the policy to route the mapping data
     *
     * @param query
     * @return a router representing a tree of operators
     */
    @Override
    public OpRouter generateRoutingPolicy(Query query) {
        ElementGroup group = (ElementGroup) query.getQueryPattern();

        /* devide operators into three groups */
        ArrayList<ElementFilter> filters = new ArrayList<ElementFilter>();
        ArrayList<ElementBind> binds = new ArrayList<ElementBind>();
        ArrayList<OpStream> streamOps = new ArrayList<OpStream>();
        ArrayList<ElementNamedGraph> graphOps = new ArrayList<ElementNamedGraph>();
        ArrayList<Op> others = new ArrayList<Op>();
        for (Element el : group.getElements()) {
            if (el instanceof ElementFilter) {
                filters.add((ElementFilter) el);
                continue;
            }
            if (el instanceof ElementBind) {
                binds.add((ElementBind) el);
                continue;
            }
            if (el instanceof ElementStreamGraph) {
                addStreamOp(streamOps, (ElementStreamGraph) el);
                continue;
            }
            if (el instanceof ElementNamedGraph) {
                graphOps.add((ElementNamedGraph) el);
            }
            others.add(compiler.compile(el));
        }

        /* push the filter down to operators on RDF datasets */
        for (int i = 0; i < others.size(); i++) {
            Op op = others.get(i);
            for (ElementFilter filter : filters) {
                if (OpVars.mentionedVars(op).containsAll(filter.getExpr().getVarsMentioned())) {
                    op = OpFilter.filter(filter.getExpr(), op);
                }
            }
            others.set(i, op);
        }

        /*project the necessary variables */
        project(filters, streamOps, others, query);

        /* Initialize query execution context, download named graph, create cache?.... */
        for (String uri : query.getNamedGraphURIs()) {
            //FIXME Ignores the default graphs. Must be fixed!
            for (ElementNamedGraph graph : graphOps) {
                Node graphNode = graph.getGraphNameNode();
                if (!context.getDataset().containsGraph(graphNode)) {
                    System.out.println(" load" + uri);
                    context.loadDataset(graphNode.getURI(), uri);
                }
            }
        }

        /* create Leaf cache from the operator over RDF datasets */
        ArrayList<BDBGraphPatternRouter> caches = new ArrayList<BDBGraphPatternRouter>();
        for (Op op : others) {
            caches.add(new BDBGraphPatternRouter(context, op));
        }

        /* create router for window operators */
        ArrayList<IndexedTripleRouter> windows = new ArrayList<IndexedTripleRouter>();
        for (OpStream op : streamOps) {
            
            Quad quad = new Quad(op.getGraphNode(), op.getBasicPattern().get(0));
            EPStatement stmt = context.engine().addWindow(quad, ".win:length(1)");
            IndexedTripleRouter router = new IndexedTripleRouter(
                    context, stmt, op);
            stmt.setSubscriber(router);
            windows.add(router);
        }
        /*
         * create routing plan for each window operator
         * how to route ???? hash operator is not unique
         */
        int i = 0;
        ArrayList<OpRouter> dataflows = new ArrayList<OpRouter>();
        for (IndexedTripleRouter router : windows) {
            BitSet wFlag = new BitSet(windows.size());
            wFlag.set(i++);
            BitSet cFlag = new BitSet(others.size());
            BitSet fFlag = new BitSet(filters.size());

            Op curOp = router.getOp();
            OpRouter curRouter = router;
            Set<Var> curVars = (Set<Var>) OpVars.mentionedVars(curOp);
            int count = 1;
            int curCount = 1;

            while (wFlag.size() + cFlag.size() + fFlag.size() > count) {
                curCount = count;
                boolean skip = false;
                for (int j = 0; j < filters.size(); j++) {
                    if ((!fFlag.get(j)) && curVars.containsAll(
                            filters.get(j).getExpr().getVarsMentioned())) {
                        curOp = OpFilter.filter(filters.get(j).getExpr(), curOp);
                        OpRouter newRouter = new FilterExprRouter(context, (OpFilter) curOp, curRouter);
                        curRouter = addRouter(curRouter, newRouter);
                        fFlag.set(j);
                        count++;
                    }
                }

                for (ElementBind b : binds) {
                    if (curVars.containsAll(b.getExpr().getVarsMentioned())) {
                        curOp = OpExtend.extend(curOp, b.getVar(), b.getExpr());
                        OpRouter newRouter = new ExtendRouter(
                                context, (OpExtend) curOp, curRouter);
                        curRouter = addRouter(curRouter, newRouter);
                    }
                }

                for (int j = 0; j < caches.size() && (!skip); j++) {
                    if ((!cFlag.get(j)) && SetUtils.intersectionP(curVars,
                            (Set<Var>) OpVars.mentionedVars(caches.get(j).getOp()))) {
                        curOp = OpJoin.create(curOp, caches.get(j).getOp());
                        OpRouter newRouter = new JoinRouter(context, (OpJoin) curOp,
                                curRouter, caches.get(j));
                        curRouter = addRouter(curRouter, newRouter);
                        cFlag.set(j);
                        curVars.addAll(OpVars.mentionedVars(caches.get(j).getOp()));
                        count++;
                        skip = true;
                    }
                }

                for (int j = 0; j < windows.size() && (!skip); j++) {
                    if ((!wFlag.get(j)) && SetUtils.intersectionP(curVars,
                            (Set<Var>) OpVars.mentionedVars(windows.get(j).getOp()))) {
                        curOp = OpJoin.create(curOp, windows.get(j).getOp());
                        OpRouter newRouter = new JoinRouter(context, (OpJoin) curOp,
                                curRouter, windows.get(j));
                        curRouter = addRouter(curRouter, newRouter);
                        wFlag.set(j);
                        curVars.addAll(OpVars.mentionedVars(windows.get(j).getOp()));
                        count++;
                        skip = true;
                    }
                }
                if (curCount == count) {
                    break;
                }
            }
            dataflows.add(curRouter);
        }

        ThroughRouter througRouter = new ThroughRouter(context, dataflows);
        for (OpRouter r : dataflows) {
            next.put(r.getId(), througRouter);
        }

        //put all into routing policy
        //return compileModifiers(query, througRouter);
        return compiler.compileModifiers(query, througRouter);
    }

    /**
     * create the relationship between 2 router nodes. This version uses hash
     * table to identify which router will be the next for the mapping to go
     *
     * @param from the departing router
     * @param newRouter the arriving router
     * @return the arriving router
     */
    @Override
    public OpRouter addRouter(OpRouter from, OpRouter newRouter) {
        next.put(from.getId(), newRouter);
        return newRouter;
    }
    
    @Override
    public void removeRouter(OpRouter from, OpRouter to) {
        next.remove(from.getId(), to);
    }

    /**
     * get the next router from the current router
     *
     * @param curRouter current router
     * @param mapping
     * @return the next router
     */
    public OpRouter next(OpRouter curRouter, Mapping mapping) {
        //System.out.println("next"+next.get(curRouter.getId()));
        return next.get(curRouter.getId());
    }

    /**
     * register the select-type query with the engine
     *
     * @param query
     * @return
     */
    public ContinuousSelect registerSelectQuery(Query query) {
        OpRouter qR = generateRoutingPolicy(query);
        if (query.isSelectType()) {
            ///TODO
            ContinuousSelect rootRouter = (ContinuousSelect) addRouter(qR,
                    new ContinuousSelect(context, query, qR));
            rootRouter.visit(new TimerVisitor());
            return rootRouter;
        }
        return null;
    }

    /**
     * register the construct-type query with the engine
     *
     * @param query
     * @return
     */
    public ContinuousConstruct registerConstructQuery(Query query) {
        OpRouter qR = generateRoutingPolicy(query);
        if (query.isConstructType()) {
            ///TODO
            ContinuousConstruct rootRouter = (ContinuousConstruct) addRouter(qR,
                    new ContinuousConstruct(context, query, qR));
            return rootRouter;
        }
        return null;
    }

    /**
     * @param filters
     * @param streamOps
     * @param others
     */
    private void project(ArrayList<ElementFilter> filters,
            ArrayList<OpStream> streamOps, ArrayList<Op> others, Query query) {
        //Variables available on the upper level
        HashSet<Var> upperVars = new HashSet<Var>();
        upperVars.addAll(query.getProjectVars());
        if (query.hasGroupBy()) {
            upperVars.addAll(query.getGroupBy().getVars());
            for (ExprAggregator agg : query.getAggregators()) {
                upperVars.addAll(agg.getVarsMentioned());
            }
        }

        if (query.hasHaving()) {
            if (query.hasHaving()) {
                for (Expr expr : query.getHavingExprs()) {
                    upperVars.addAll(expr.getVarsMentioned());
                }
            }
        }

        for (ElementFilter filter : filters) {
            upperVars.addAll(filter.getExpr().getVarsMentioned());
            //System.out.println(upperVars);
        }

        for (OpStream op : streamOps) {
            OpVars.mentionedVars(op, upperVars);
            //System.out.println(upperVars);
        }

        for (int i = 0; i < others.size(); i++) {
            Op op = others.get(i);
            Set<Var> opVars = (Set<Var>) OpVars.mentionedVars(op);
            ArrayList<Var> projectedVars = new ArrayList<Var>();
            for (Var var : opVars) {
                if (upperVars.contains(var)) {
                    projectedVars.add(var);
                }
            }
            if (projectedVars.size() < opVars.size()) {
                others.set(i, new OpDistinct(new OpProject(op, projectedVars)));
            } else {
                others.set(i, new OpDistinct(op));
            }
        }
    }

    private void addStreamOp(ArrayList<OpStream> streamOps, ElementStreamGraph el) {
        /*if(el.getWindow()==null){
         System.out.println("null");
         }
         else System.out.println(el.getWindow().getClass());*/
        if (el.getElement() instanceof ElementTriplesBlock) {
            addStreamOp(streamOps, (ElementTriplesBlock) el.getElement(),
                    el.getGraphNameNode(), el.getWindow());
        } else if (el.getElement() instanceof ElementGroup) {
            addStreamOp(streamOps, (ElementGroup) el.getElement(),
                    el.getGraphNameNode(), el.getWindow());
        } else {
            System.out.println("Stream pattern is not ElementTripleBlock" + el.getElement().getClass());
        }
    }

    private void addStreamOp(ArrayList<OpStream> streamOps,
            ElementGroup group, Node graphNode, Window window) {
        for (Element el : group.getElements()) {
            if (el instanceof ElementTriplesBlock) {
                addStreamOp(streamOps, (ElementTriplesBlock) el, graphNode, window);
            }
            if (el instanceof ElementPathBlock) {
                for (Iterator<TriplePath> paths = ((ElementPathBlock) el).patternElts(); paths.hasNext();) {
                    Triple t = paths.next().asTriple();
                    if (t != null) {
                        streamOps.add(new OpStream(graphNode, t, window));
                    } else {
                        System.out.println("Path is not supported");
                    }
                }
            } else {
                System.out.println("unrecognized block" + el.getClass());
            }
        }
    }

    private void addStreamOp(ArrayList<OpStream> streamOps,
            ElementTriplesBlock el, Node graphNode, Window window) {
        for (Triple t : el.getPattern().getList()) {
            streamOps.add(new OpStream(graphNode, t, window));
        }
    }
}
