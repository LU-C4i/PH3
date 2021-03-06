package org.c4i.nlp.ph3.match;

import org.c4i.nlp.ph3.tokenize.Token;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluate a matching rule.
 * The rule should be in CNF.
 *
 * @author Arvid
 * @version 27-4-2016 - 21:24
 */
public class MatchEval {

    public MatchEval() {
    }

    /**
     * Reurn all matching labels
     * @param tokens
     * @return
     */
    public static Map<String, MatchRange> eval(final MatchRuleSet ruleSet, final Token[] tokens){
        final Map<String, MatchRange> result = new ConcurrentHashMap<>();
        ruleSet.rules.values().forEach(rule -> {
            int[] range = MatchEval.findRange(tokens, rule, ruleSet, result);
            if(range != null){
                result.put(rule.head, new MatchRange(rule.head, range[0], range[1], tokens[range[0]].getCharStart(), tokens[range[1]-1].getCharEnd()));
            }
        });
        return result;
    }

    /**
     * Reurn all matching labels
     * @param tokens
     * @return
     */
    public static Map<String, MatchRange> evalParallel(final MatchRuleSet ruleSet, final Token[] tokens){
        final Map<String, MatchRange> result = new ConcurrentHashMap<>();
        ruleSet.rules.values().stream().parallel().forEach(rule -> {
            int[] range = MatchEval.findRange(tokens, rule, ruleSet, result);
            if(range != null){
                result.put(rule.head, new MatchRange(rule.head, range[0], range[1], tokens[range[0]].getCharStart(), tokens[range[1]-1].getCharEnd()));
            }
        });
        return result;
    }

    public static String highlight(String orgText, Map<String, MatchRange> eval){
        StringBuilder sb = new StringBuilder();
        Collection<MatchRange> ranges = eval.values();
        for (int i = 0; i < orgText.length(); i++) {
            for (MatchRange range : ranges) {
                if(range.charStart == i){
                    sb.append("<span class=\"match ").append(range.label).append("\">");
                }
                if(range.charEnd == i){
                    sb.append("</span>");
                }
            }
            char c = orgText.charAt(i);
            if(c == '\n'){
                sb.append("<br/>");
            }
            sb.append(c);
        }

        for (MatchRange range : ranges) {
            if(range.charEnd == orgText.length()){
                sb.append("</span>");
            }
        }

        return sb.toString();
    }

    /**
     * Apply a rule in CNF to a list of tokens
     * @param text the list of tokens (text) to search in
     * @param rule match rule
     * @return whether the rule matches or not
     */
    public static boolean contains(final Token[] text, final MatchRule rule){
        return findRange(text, rule, null, null) != null;
    }

    /**
     * Apply a rule in CNF to a list of tokens
     * @param text the list of tokens (text) to search in
     * @param rule match rule
     * @return whether the rule matches or not
     */
    public static int[] findRange(final Token[] text, final MatchRule rule, final MatchRuleSet context, final Map<String, MatchRange> result){
        final int S = text.length;
        if(rule.expression == null || rule.expression.length == 0){
            // no constraints, match entire text
            return new int[]{0, S};
        }

        int[] rangeFound = null;
        HashMap<Literal, int[]> cache = new HashMap<>();

        for (Literal[] disjunction : rule.expression) {
            int[] disjunctionRange = null;

            for (Literal lit : disjunction) {
                if(cache.containsKey(lit)) {
                    int[] cachedRange = cache.get(lit);
                    if (cachedRange != null) {
                        disjunctionRange = cachedRange; // already known to be true
                        break;
                    } else {
                        continue; // already known to be false
                    }
                }


                if(lit.meta == '#' && context != null){
                    if(result != null && result.containsKey(rule.head)){
                        // already known to be true
                        MatchRange matchRange = result.get(rule.head);
                        disjunctionRange = new int[]{matchRange.tokenStart, matchRange.tokenEnd};
                        break;
                    } else {
                        // perform lookup
                        disjunctionRange = findRange(text, context.rules.get(lit.tokens[0].getWord()), context, result);
                    }

                } else {
                    // evaluate the match
                    disjunctionRange = findRange(text, lit);
                }

                cache.put(lit, disjunctionRange);
                if(disjunctionRange != null){
                    break; // matched! next disjunction please...
                }
            }

            if(disjunctionRange == null){
                // this prop is false, therefore the cnf is false
                rangeFound = null;
                break;
            } else {
                // update result range
                if (rangeFound == null) {
                    rangeFound = disjunctionRange;
                } else {
                    // extend bound
                    rangeFound[1] = disjunctionRange[1];
                }
            }
        }

        return rangeFound;
    }

    private static boolean contains(final Token[] text, final Literal lit){
        int[] range = findRange(text, lit);
        return !(range == null || range[0] < 0);
    }

    public static int[] findRange(final Token[] text, final Literal pattern){
        final int T = text.length;
        final int P = pattern.tokens.length;

        int matchStart = Integer.MAX_VALUE;
        int matchEnd = -1;

        boolean pNeg = pattern.negated;

        nextS : for (int si = 0; si < T; si++) {
            int pi;

            nextP: for (pi = 0; pi < P; pi++) {

                Token p = pattern.tokens[pi];

                if(si + pi >= T){
                    return pNeg ? new int[]{0, T} : null;
                }
                Token s = text[si + pi];

                if ("?".equals(p.getWord())) {
                    matchStart = Math.min(si, matchStart);
                    matchEnd = Math.max(si + pi + 1, matchEnd);
                    continue nextP;
                } else if ("+".equals(p.getWord()) || "*".equals(p.getWord())) {
                    pi++;
                    if(pi == P){
                        matchStart = Math.min(si, matchStart);
                        matchEnd = Math.max(si + pi, matchEnd);
                        return pNeg ? null : new int[]{matchStart, matchEnd}; // last pattern token
                    }

                    Token pWildCard = p;
                    p = pattern.tokens[pi];  // next p
                    if("?".equals(p.getWord()) || "*".equals(p.getWord()) || "+".equals(p.getWord())){
                        throw new IllegalArgumentException("Pattern contains multiple wildcards next to each other: " + pattern);
                    }
                    if(pi + si == T){
                        return pNeg ? new int[]{0, T} : null; // no more tokens in sentence, but pattern expects token
                    }

                    // find first match for p
                    for (int k = si + pi - ("*".equals(pWildCard.getWord()) ? 1 : 0); k < T; k++) {
                        if(text[k].equals(p)){
                            si = k;
                            matchEnd = si + 1;
                            continue nextP;
                        }
                    }
                    matchStart = Integer.MAX_VALUE;
                    matchEnd = -1;
                    continue nextS; // next p not found
                }

                // literal check
                if (!s.equals(p)) {
                    // not matching
                    matchStart = Integer.MAX_VALUE;
                    matchEnd = -1;
                    continue nextS;
                } else {
                    // match
                    matchStart = Math.min(si, matchStart);
                    matchEnd = Math.max(si + pi + 1, matchEnd);
                }
            }

            if(pi == P){
                // full pattern matched
                return pNeg ? null : new int[]{matchStart, matchEnd};
            }
        }
        return pNeg ? new int[]{0, T} : null;
    }


}
