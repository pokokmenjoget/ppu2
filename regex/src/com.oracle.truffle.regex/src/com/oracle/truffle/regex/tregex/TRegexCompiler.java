/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.regex.CompiledRegexObject;
import com.oracle.truffle.regex.RegexCompiler;
import com.oracle.truffle.regex.RegexLanguage;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.RegexSyntaxException;
import com.oracle.truffle.regex.tregex.nfa.NFA;
import com.oracle.truffle.regex.tregex.nodes.TRegexExecRootNode;
import com.oracle.truffle.regex.tregex.nodes.TRegexExecRootNode.LazyCaptureGroupRegexSearchNode;
import com.oracle.truffle.regex.tregex.nodes.dfa.TRegexDFAExecutorNode;
import com.oracle.truffle.regex.tregex.nodes.nfa.TRegexBacktrackingNFAExecutorNode;
import com.oracle.truffle.regex.tregex.parser.flavors.RegexFlavor;
import com.oracle.truffle.regex.tregex.parser.flavors.RegexFlavorProcessor;

public final class TRegexCompiler implements RegexCompiler {

    private final RegexLanguage language;

    public TRegexCompiler(RegexLanguage language) {
        this.language = language;
    }

    public RegexLanguage getLanguage() {
        return language;
    }

    @TruffleBoundary
    @Override
    public CompiledRegexObject compile(RegexSource source) throws RegexSyntaxException {
        return compile(language, source);
    }

    @TruffleBoundary
    public static CompiledRegexObject compile(RegexLanguage language, RegexSource source) throws RegexSyntaxException {
        RegexFlavor flavor = source.getOptions().getFlavor();
        RegexSource ecmascriptSource = source;
        if (flavor != null) {
            /*
             * We rewrite the pattern here, to avoid rewriting again when switching to other
             * matching strategies via the other compile* methods below.
             */
            RegexFlavorProcessor flavorProcessor = flavor.forRegex(source);
            ecmascriptSource = flavorProcessor.toECMAScriptRegex();
        }
        return new TRegexCompilationRequest(language, ecmascriptSource).compile();
    }

    @TruffleBoundary
    public static TRegexDFAExecutorNode compileEagerDFAExecutor(RegexLanguage language, RegexSource source) {
        return new TRegexCompilationRequest(language, source).compileEagerDFAExecutor();
    }

    @TruffleBoundary
    public static LazyCaptureGroupRegexSearchNode compileLazyDFAExecutor(RegexLanguage language, NFA nfa, TRegexExecRootNode rootNode, boolean allowSimpleCG) {
        return new TRegexCompilationRequest(language, nfa).compileLazyDFAExecutor(rootNode, allowSimpleCG);
    }

    @TruffleBoundary
    public static TRegexBacktrackingNFAExecutorNode compileBacktrackingExecutor(RegexLanguage language, NFA nfa) {
        return new TRegexCompilationRequest(language, nfa).compileBacktrackingExecutor();
    }
}
