package io.github.ajichama.citrusbrowser.legacy.pattern;

import io.github.ajichama.citrusbrowser.legacy.utils.matcher.AbstractPatternChecker;

public abstract class PatternChecker extends AbstractPatternChecker<PatternAction> {
    protected PatternChecker(PatternAction pattern_action) {
        super(pattern_action);
    }
}
