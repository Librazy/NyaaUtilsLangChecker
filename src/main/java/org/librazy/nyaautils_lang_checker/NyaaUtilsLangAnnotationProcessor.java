package org.librazy.nyaautils_lang_checker;

import com.google.common.collect.HashBasedTable;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.lang.reflect.Constructor;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.sun.source.tree.Tree.Kind.*;

@SupportedAnnotationTypes({
        "org.librazy.nyaautils_lang_checker.LangKey",
})
@SupportedOptions({
        "LANG_SHOW_DEBUG",
        "LANG_SHOW_NOTE",
        "LANG_DIR_FULL_PATH",
        "LANG_DIR_ADDITIONAL_PATH",
        "CLASS_OUTPUT_DIR_REGEX_PATH",
        "LANG_DIR_REGEX_PATH",
        "LANG_FILE_EXT",
})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class NyaaUtilsLangAnnotationProcessor extends AbstractProcessor implements Plugin, TaskListener {

    private final static Map<String, Map<String, String>> map = new HashMap<>();
    private final static Map<String, Map<String, String>> internalMap = new HashMap<>();
    private final static Map<String, Map<String, String>> additionalMap = new HashMap<>();
    private static ProcessingEnvironment processingEnvironment;
    private static Trees trees;
    private static Types typeUtils;

    /**
     * add all language items from section into language map recursively
     * overwrite existing items
     * The '&' will be transformed to color code.
     *
     * @param section        source section
     * @param prefix         used in recursion to determine the proper prefix
     * @param ignoreInternal ignore keys prefixed with `internal'
     * @param ignoreNormal   ignore keys not prefixed with `internal'
     */
    private static void loadLanguageSection(Map<String, String> map, ConfigurationSection section, String prefix, boolean ignoreInternal, boolean ignoreNormal) {
        if (map == null || section == null || prefix == null) return;
        for (String key : section.getKeys(false)) {
            String path = prefix + key;
            if (section.isString(key)) {
                if (path.startsWith("internal") && ignoreInternal) continue;
                if (!path.startsWith("internal") && ignoreNormal) continue;
                map.put(path, ChatColor.translateAlternateColorCodes('&', section.getString(key)));
            } else if (section.isConfigurationSection(key)) {
                loadLanguageSection(map, section.getConfigurationSection(key), path + ".", ignoreInternal, ignoreNormal);
            }
        }
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        try {
            processingEnvironment = processingEnv;
            trees = Trees.instance(processingEnv);
            typeUtils = processingEnvironment.getTypeUtils();

            super.init(processingEnv);
            Map<String, String> options = this.processingEnv.getOptions();
            Boolean showNote = options.getOrDefault("LANG_SHOW_NOTE", "true").equalsIgnoreCase("true");
            Boolean showDebug = options.getOrDefault("LANG_SHOW_DEBUG", "false").equalsIgnoreCase("true");
            BiConsumer<Diagnostic.Kind, String> msg = (kind, message) -> {
                if (kind == Diagnostic.Kind.OTHER && !showDebug) return;
                if (kind == Diagnostic.Kind.NOTE && !showNote) return;
                processingEnv.getMessager().printMessage(kind, message);
            };

            String path = options.get("LANG_DIR_FULL_PATH");
            msg.accept(Diagnostic.Kind.OTHER, String.format("LANG_DIR_FULL_PATH: %s", path));
            String additionalPath = options.get("LANG_DIR_ADDITIONAL_PATH");
            msg.accept(Diagnostic.Kind.OTHER, String.format("LANG_DIR_ADDITIONAL_PATH: %s", additionalPath));

            if (path == null) {
                String pathRegex = options.get("CLASS_OUTPUT_DIR_REGEX_PATH") == null ? "build/classes/(main/|test/)?" : options.get("CLASS_OUTPUT_DIR_REGEX_PATH");
                msg.accept(Diagnostic.Kind.OTHER, String.format("CLASS_OUTPUT_DIR_REGEX_PATH: %s", pathRegex));

                String langRegex = options.get("LANG_DIR_REGEX_PATH") == null ? "src/main/resources/lang/" : options.get("LANG_DIR_REGEX_PATH");
                msg.accept(Diagnostic.Kind.OTHER, String.format("LANG_DIR_REGEX_PATH: %s", langRegex));

                Filer filer = processingEnv.getFiler();
                FileObject fileObject = filer.getResource(StandardLocation.CLASS_OUTPUT, "", "langChecker");
                path = "/" + URLDecoder.decode(fileObject.toUri().toString().replaceFirst(pathRegex + "langChecker", langRegex), StandardCharsets.UTF_8.name()).replaceFirst("file:/", "");
                msg.accept(Diagnostic.Kind.OTHER, String.format("LANG_DIR_FULL_PATH(from reg replace): %s", path));
            }
            File f = new File(path);
            String langExt = options.get("LANG_FILE_EXT") == null ? ".yml" : options.get("LANG_FILE_EXT");
            if (f.getPath().endsWith("langChecker")) {
                msg.accept(Diagnostic.Kind.WARNING, "Failed to locate lang resources! Please use LANG_DIR_FULL_PATH!");
                return;
            }
            msg.accept(Diagnostic.Kind.NOTE, "Lang resources directory: " + f.getCanonicalPath());
            File[] mainFiles = f.listFiles(file -> file.isFile() && file.getPath().endsWith(langExt));
            if (mainFiles == null) {
                msg.accept(Diagnostic.Kind.WARNING, "Lang resources not found!");
                return;
            }
            List<File> langFiles = Arrays.asList(mainFiles);
            List<File> additionalFiles = null;
            if (additionalPath != null) {
                File additionalDir = new File(additionalPath);
                try {
                    msg.accept(Diagnostic.Kind.NOTE, "Additional lang resources directory: " + additionalDir.getCanonicalPath());
                } catch (IOException e) {
                    e.printStackTrace();
                    msg.accept(Diagnostic.Kind.WARNING, "Fail to load additional lang resources directory: " + additionalPath);
                }
                File[] files = additionalDir.listFiles(file -> file.isFile() && file.getPath().endsWith(langExt));
                if (files != null) {
                    additionalFiles = Arrays.asList(files);
                }
            }
            msg.accept(Diagnostic.Kind.NOTE, "Loaded lang files:");
            for (File file : langFiles) {
                try {
                    msg.accept(Diagnostic.Kind.NOTE, file.getCanonicalPath());
                } catch (IOException e) {
                    msg.accept(Diagnostic.Kind.WARNING, "Fail to load lang file: " + file.getPath());
                    msg.accept(Diagnostic.Kind.WARNING, e.getMessage());
                }
            }
            if (additionalFiles != null) {
                msg.accept(Diagnostic.Kind.NOTE, "Loaded additional lang files:");
                for (File file : additionalFiles) {
                    try {
                        msg.accept(Diagnostic.Kind.NOTE, file.getCanonicalPath());
                    } catch (IOException e) {
                        msg.accept(Diagnostic.Kind.WARNING, "Fail to load additional lang file: " + file.getPath());
                        msg.accept(Diagnostic.Kind.WARNING, e.getMessage());
                    }
                }
            }
            loadInternalMap(additionalFiles, additionalMap);
            loadInternalMap(langFiles, internalMap);
            loadLanguageMap(langFiles);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations,
                           final RoundEnvironment roundEnv) {
        try {
            for (final Element element : roundEnv.getElementsAnnotatedWith(LangKey.class)) {
                if (element instanceof VariableElement) {
                    LangKey annotation = element.getAnnotation(LangKey.class);
                    Object constValue;
                    try {
                        constValue = ((VariableElement) element).getConstantValue();
                    } catch (ClassCastException e) {
                        continue;
                    }
                    if (constValue == null || !(constValue instanceof String)) continue;
                    String key = (String) constValue;
                    if (annotation.type() == LangKeyType.KEY) {
                        checkKey(annotation, key, element);
                    } else if (annotation.type() == LangKeyType.PREFIX) {
                        checkKeyPrefix(annotation, key, element);
                    } else {
                        checkKeyInSuffix(annotation, key, element);
                    }
                } else if (element instanceof TypeElement) {
                    LangKey annotation = element.getAnnotation(LangKey.class);
                    TypeElement te = ((TypeElement) element);
                    if (te.getKind() == ElementKind.ENUM) {
                        List<VariableElement> variableElements =
                                te.getEnclosedElements()
                                  .stream()
                                  .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                                  .map(e -> (VariableElement) e).collect(Collectors.toList());
                        switch (annotation.type()) {
                            case KEY:
                                variableElements.forEach(e -> checkKey(annotation, e.toString(), element));
                                break;
                            case PREFIX:
                                variableElements.forEach(e -> checkKeyPrefix(annotation, e.toString(), element));
                                break;
                            case INFIX:
                            case SUFFIX:
                                variableElements.forEach(e -> checkKeyInSuffix(annotation, e.toString(), element));
                                break;
                        }
                    }
                }
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void loadInternalMap(List<File> files, Map<String, Map<String, String>> map) {
        if (files == null) return;
        map.clear();
        for (File f : files) {
            try {
                InputStream stream = new FileInputStream(f);
                ConfigurationSection section = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
                map.put(f.getName(), new HashMap<>());
                loadLanguageSection(map.get(f.getName()), section.getConfigurationSection("internal"), "internal.", false, true);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadLanguageMap(List<File> files) {
        map.clear();
        for (File f : files) {
            try {
                InputStream stream = new FileInputStream(f);
                ConfigurationSection section = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
                map.put(f.getName(), new HashMap<>());
                loadLanguageSection(map.get(f.getName()), section, "", true, false);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public String getName() {
        return NyaaUtilsLangAnnotationProcessor.class.getSimpleName();
    }

    @Override
    public void init(JavacTask task, String... args) {
        task.addTaskListener(this);
    }

    @Override
    public void finished(TaskEvent taskEvt) {
        try {
            if (taskEvt.getKind() == TaskEvent.Kind.ANALYZE && (map.size() != 0 || internalMap.size() != 0)) {
                taskEvt.getCompilationUnit().accept(new TreeScanner<Void, Void>() {
                    //from jdk8u/langtools/src/share/classes/com/sun/source/util/TreePathScanner.java

                    private TreePath path;
                    private ExecutableElement lastMethod;

                    /**
                     * Scan a single node.
                     * The current path is updated for the duration of the scan.
                     */
                    @Override
                    public Void scan(Tree tree, Void v) {
                        if (tree == null)
                            return null;

                        TreePath prev = path;
                        path = new TreePath(path, tree);
                        try {
                            return tree.accept(this, v);
                        } finally {
                            path = prev;
                        }
                    }

                    @Override
                    public Void visitCompilationUnit(CompilationUnitTree node, Void v) {
                        path = new TreePath(null, node);
                        super.visitCompilationUnit(node, v);
                        return v;
                    }

                    @Override
                    public Void visitReturn(ReturnTree returnTree, Void v) {
                        ExpressionTree exp = returnTree.getExpression();
                        if (lastMethod == null) {
                            return super.visitReturn(returnTree, v);
                        }
                        LangKey actualAnnotation = lastMethod.getAnnotation(LangKey.class);
                        if (actualAnnotation == null) {
                            return super.visitReturn(returnTree, v);
                        }
                        if (!actualAnnotation.skipCheck()) {
                            visitExpressionTree(taskEvt, actualAnnotation, exp, true, true);
                        }
                        return super.visitReturn(returnTree, v);
                    }

                    @Override
                    public Void visitVariable(VariableTree variableTree, Void v) {
                        ExpressionTree exp = variableTree.getInitializer();
                        if (exp == null) return super.visitVariable(variableTree, v);
                        TreePath path = TreePath.getPath(taskEvt.getCompilationUnit(), variableTree);
                        Element element = trees.getElement(path);
                        if (element == null) {
                            return super.visitVariable(variableTree, v);
                        }
                        LangKey actualAnnotation = element.getAnnotation(LangKey.class);
                        if (actualAnnotation == null) {
                            return super.visitVariable(variableTree, v);
                        }
                        if (!actualAnnotation.skipCheck()) {
                            visitExpressionTree(taskEvt, actualAnnotation, exp, true, true);
                        }
                        return super.visitVariable(variableTree, v);
                    }

                    @Override
                    public Void visitAssignment(AssignmentTree assignmentTree, Void v) {
                        ExpressionTree var = assignmentTree.getVariable();
                        ExpressionTree exp = assignmentTree.getExpression();
                        TreePath path = TreePath.getPath(taskEvt.getCompilationUnit(), var);
                        Element element = trees.getElement(path);
                        if (element == null) {
                            return super.visitAssignment(assignmentTree, v);
                        }
                        LangKey actualAnnotation = element.getAnnotation(LangKey.class);
                        if (actualAnnotation == null) {
                            return super.visitAssignment(assignmentTree, v);
                        }
                        if (!actualAnnotation.skipCheck()) {
                            visitExpressionTree(taskEvt, actualAnnotation, exp, true, true);
                        }
                        return super.visitAssignment(assignmentTree, v);
                    }

                    @Override
                    public Void visitMethod(MethodTree methodTree, Void v) {
                        TreePath path = TreePath.getPath(taskEvt.getCompilationUnit(), methodTree);
                        Element element = trees.getElement(path);
                        lastMethod = (ExecutableElement) element;
                        return super.visitMethod(methodTree, v);
                    }

                    @Override
                    public Void visitLambdaExpression(LambdaExpressionTree methodTree, Void v) {
                        ExecutableElement last = lastMethod;
                        try {
                            lastMethod = null;
                            return super.visitLambdaExpression(methodTree, v);
                        } finally {
                            lastMethod = last;
                        }
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree methodInv, Void v) {
                        ExecutableElement method;
                        try {
                            method = (ExecutableElement) trees.getElement(path);
                        } catch (ClassCastException e) {
                            return super.visitMethodInvocation(methodInv, v);
                        }
                        if (method == null) return super.visitMethodInvocation(methodInv, v);
                        if (method.getParameters().stream().anyMatch(var -> var.getAnnotation(LangKey.class) != null)) {
                            List<LangKey> langKeyList = method.getParameters().stream().map(var -> var.getAnnotation(LangKey.class)).collect(Collectors.toList());
                            List<? extends ExpressionTree> rawArgumentList = methodInv.getArguments();
                            visitArgList(langKeyList, rawArgumentList, taskEvt);
                        }
                        return super.visitMethodInvocation(methodInv, v);
                    }

                    @Override
                    public Void visitNewClass(NewClassTree methodInv, Void v) {
                        ExecutableElement method;
                        try {
                            method = (ExecutableElement) trees.getElement(path);
                        } catch (ClassCastException e) {
                            return super.visitNewClass(methodInv, v);
                        }
                        if (method == null) return super.visitNewClass(methodInv, v);
                        if (method.getParameters().stream().anyMatch(var -> var.getAnnotation(LangKey.class) != null)) {
                            List<LangKey> langKeyList = method.getParameters().stream().map(var -> var.getAnnotation(LangKey.class)).collect(Collectors.toList());
                            List<? extends ExpressionTree> rawArgumentList = methodInv.getArguments();
                            visitArgList(langKeyList, rawArgumentList, taskEvt);
                        }
                        return super.visitNewClass(methodInv, v);
                    }
                }, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void visitArgList(List<LangKey> langKeyList, List<? extends ExpressionTree> rawArgumentList, TaskEvent taskEvt) {
        Iterator<LangKey> langKeyIterator = langKeyList.iterator();
        Iterator<? extends ExpressionTree> rawArgumentIterator = rawArgumentList.iterator();

        while (langKeyIterator.hasNext() && rawArgumentIterator.hasNext()) {
            LangKey annotation = langKeyIterator.next();
            ExpressionTree tree = rawArgumentIterator.next();
            Consumer<String> treesWarn = (String warn) -> trees.printMessage(Diagnostic.Kind.WARNING, warn, tree, taskEvt.getCompilationUnit());
            if (annotation != null) {
                HashBasedTable<String, String, String> valueEntries = visitExpressionTree(taskEvt, annotation, tree, true, true);
                if (valueEntries != null && !valueEntries.isEmpty()) {
                    if (valueEntries.values().stream().anyMatch(str -> Pattern.compile("(%[sdf]|%\\.\\df)").matcher(str).find())) {
                        List<Object> objects = new ArrayList<>();
                        int skip = annotation.varArgsPosition();
                        if (skip == -1) return;
                        while (skip-- != 0) {
                            rawArgumentIterator.next();
                        }
                        rawArgumentIterator.forEachRemaining(argTree -> {
                            TypeMirror tm = trees.getTypeMirror(trees.getPath(taskEvt.getCompilationUnit(), argTree));
                            if (tm.getKind().isPrimitive()) {
                                tm = typeUtils.boxedClass(typeUtils.getPrimitiveType(tm.getKind())).asType();
                            }
                            try {
                                Class<?> clazz = Class.forName(tm.toString());
                                Constructor<?> ctor;
                                try {
                                    ctor = clazz.getConstructor();
                                } catch (NoSuchMethodException noSuchMethodException) {
                                    try {
                                        ctor = clazz.getConstructor(int.class);
                                    } catch (NoSuchMethodException noSuchMethodException2) {
                                        try {
                                            ctor = clazz.getConstructor(long.class);
                                        } catch (NoSuchMethodException noSuchMethodException3) {
                                            try {
                                                ctor = clazz.getConstructor(double.class);//float has a double constructor too
                                            } catch (NoSuchMethodException noSuchMethodException4) {
                                                ctor = clazz.getConstructor(boolean.class);
                                            }
                                        }
                                    }
                                }
                                try {
                                    objects.add(ctor.newInstance());
                                } catch (IllegalArgumentException illegalArgumentException) {
                                    try {
                                        objects.add(ctor.newInstance(1));
                                    } catch (IllegalArgumentException illegalArgumentException2) {
                                        try {
                                            objects.add(ctor.newInstance(1L));
                                        } catch (IllegalArgumentException illegalArgumentException3) {
                                            try {
                                                objects.add(ctor.newInstance(0.1));
                                            } catch (IllegalArgumentException illegalArgumentException4) {
                                                objects.add(ctor.newInstance(true));
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                treesWarn.accept("Unsupported type used as lang formatter argument: " + tm.toString());
                                valueEntries.clear();
                            }
                        });
                        valueEntries.cellSet().forEach(s -> {
                            try {
                                new Formatter().format(s.getValue(), objects.toArray());
                            } catch (IllegalFormatException e) {
                                treesWarn.accept("Corrupted key " + s.getColumnKey() + " in " + s.getRowKey() + ": " + s.getValue());
                            }
                        });
                    }
                }
                return;
            }
        }
    }

    private HashBasedTable<String, String, String> visitExpressionTree(TaskEvent taskEvt, LangKey expectingAnnotation, final ExpressionTree expressionTree, boolean canBePrefix, boolean canBeSuffix) {
        TreePath path = TreePath.getPath(taskEvt.getCompilationUnit(), expressionTree);
        Element element = trees.getElement(path);
        Consumer<String> treesWarn = (String warn) -> trees.printMessage(Diagnostic.Kind.WARNING, warn, expressionTree, taskEvt.getCompilationUnit());
        try {
            if (element == null) {
                if (expressionTree.getKind() == STRING_LITERAL) {
                    String value = (String) ((LiteralTree) expressionTree).getValue();
                    if (expectingAnnotation.type() == LangKeyType.KEY) {
                        if (canBePrefix && canBeSuffix) {
                            return checkKey(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit());
                        } else if (canBePrefix) {
                            checkKeyPrefix(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit());
                        } else {
                            checkKeyInSuffix(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit(), canBeSuffix);
                        }
                    } else if (expectingAnnotation.type() == LangKeyType.PREFIX) {
                        if (canBePrefix) {
                            checkKeyPrefix(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit());
                        } else {
                            checkKeyInSuffix(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit(), canBeSuffix);
                        }
                    } else if (expectingAnnotation.type() == LangKeyType.SUFFIX) {
                        if (canBeSuffix) {
                            checkKeyInSuffix(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit(), true);
                        } else {
                            checkKeyInSuffix(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit(), false);
                        }
                    } else {
                        checkKeyInSuffix(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit(), false);
                    }
                } else {
                    if (expressionTree.getKind() == PLUS) {
                        BinaryTree bt = (BinaryTree) expressionTree;
                        ExpressionTree lo = bt.getLeftOperand();
                        ExpressionTree ro = bt.getRightOperand();
                        visitExpressionTree(taskEvt, expectingAnnotation, lo, canBePrefix, false);
                        visitExpressionTree(taskEvt, expectingAnnotation, ro, false, canBeSuffix);
                    } else if (expressionTree.getKind() == PARENTHESIZED) {
                        ParenthesizedTree pt = (ParenthesizedTree) expressionTree;
                        visitExpressionTree(taskEvt, expectingAnnotation, pt.getExpression(), canBePrefix, canBeSuffix);
                    } else if (expressionTree.getKind() == CONDITIONAL_EXPRESSION) {
                        ConditionalExpressionTree ct = (ConditionalExpressionTree) expressionTree;
                        visitExpressionTree(taskEvt, expectingAnnotation, ct.getTrueExpression(), canBePrefix, canBeSuffix);
                        visitExpressionTree(taskEvt, expectingAnnotation, ct.getFalseExpression(), canBePrefix, canBeSuffix);
                    } else {
                        treesWarn.accept("Using raw value " + expressionTree.getKind().toString().toLowerCase() + " as lang key:");
                    }
                }
            } else if (element.getAnnotation(LangKey.class) == null) {
                if (expressionTree.getKind() == METHOD_INVOCATION) {
                    MethodInvocationTree methodInvocationTree = (MethodInvocationTree) expressionTree;
                    TreePath mtPath = TreePath.getPath(taskEvt.getCompilationUnit(), methodInvocationTree);
                    Element mtElement = trees.getElement(mtPath);
                    if (mtElement.getAnnotation(LangKey.class) == null) {
                        ExpressionTree methodSelect = methodInvocationTree.getMethodSelect();
                        if (methodSelect instanceof MemberSelectTree && (mtElement.getSimpleName().toString().equals("name") || mtElement.getSimpleName().toString().equals("toString"))) {
                            MemberSelectTree memberSelectTree = (MemberSelectTree) methodSelect;
                            ExpressionTree receiver = memberSelectTree.getExpression();
                            TreePath rPath = TreePath.getPath(taskEvt.getCompilationUnit(), receiver);
                            Element rcElement = trees.getElement(rPath);
                            TypeMirror rcType = trees.getTypeMirror(trees.getPath(taskEvt.getCompilationUnit(), receiver));
                            Element rcTypeElement = typeUtils.asElement(rcType);
                            if (rcTypeElement == null) {
                                treesWarn.accept("Using not annotated type as lang key suffix:");
                            } else {
                                LangKey actualAnnotation = rcElement.getAnnotation(LangKey.class);
                                if (actualAnnotation == null) {
                                    actualAnnotation = rcTypeElement.getAnnotation(LangKey.class);
                                }
                                if (actualAnnotation == null) {
                                    treesWarn.accept("Using not annotated enum(-like) as lang key suffix:");
                                } else {
                                    if (!actualAnnotation.skipCheck()) {
                                        checkActualAnnotation(canBePrefix, canBeSuffix, treesWarn, actualAnnotation);
                                    }
                                }
                            }
                        } else {
                            treesWarn.accept("Using not annotated method return as lang key suffix:");
                        }
                    } else {
                        LangKey actualAnnotation = mtElement.getAnnotation(LangKey.class);
                        checkActualAnnotation(canBePrefix, canBeSuffix, treesWarn, actualAnnotation);
                    }
                } else {
                    if (expressionTree.getKind() == IDENTIFIER || expressionTree.getKind() == MEMBER_SELECT) {
                        treesWarn.accept("Using not annotated variable as lang key:");
                    } else {
                        treesWarn.accept("Using not annotated element as lang key:");
                    }
                }
            } else {
                LangKey actualAnnotation = element.getAnnotation(LangKey.class);
                if (actualAnnotation.type() != expectingAnnotation.type()) {
                    if (!actualAnnotation.skipCheck() && checkExpectingAnnotation(canBePrefix, canBeSuffix, expectingAnnotation)) {
                        treesWarn.accept("Expecting " + expectingAnnotation.type().toString().toLowerCase() + ", found " + actualAnnotation.type().toString().toLowerCase() + ":");
                    }
                }
            }
        } catch (Exception e) {
            treesWarn.accept("Exception in processing:");
            e.printStackTrace();
        }
        return null;
    }

    private void checkActualAnnotation(boolean canBePrefix, boolean canBeSuffix, Consumer<String> treesWarn, LangKey actualAnnotation) {
        if (canBePrefix && canBeSuffix && (actualAnnotation.type() != LangKeyType.KEY)) {
            treesWarn.accept("Expecting key, but found " + actualAnnotation.type().toString().toLowerCase() + ":");
        }
        if (canBePrefix && !canBeSuffix && (actualAnnotation.type() != LangKeyType.PREFIX)) {
            treesWarn.accept("Expecting prefix, but found " + actualAnnotation.type().toString().toLowerCase() + ":");
        }
        if (!canBePrefix && canBeSuffix && (actualAnnotation.type() != LangKeyType.SUFFIX)) {
            treesWarn.accept("Expecting suffix, but found " + actualAnnotation.type().toString().toLowerCase() + ":");
        }
        if (!canBePrefix && !canBeSuffix && (actualAnnotation.type() != LangKeyType.INFIX)) {
            treesWarn.accept("Expecting infix, but found " + actualAnnotation.type().toString().toLowerCase() + ":");
        }
    }

    private boolean checkExpectingAnnotation(boolean canBePrefix, boolean canBeSuffix, LangKey expectingAnnotation) {
        return !(canBePrefix && canBeSuffix && (expectingAnnotation.type() != LangKeyType.KEY))
                && !(canBePrefix && !canBeSuffix && (expectingAnnotation.type() != LangKeyType.PREFIX))
                && !(!canBePrefix && canBeSuffix && (expectingAnnotation.type() != LangKeyType.SUFFIX))
                && !(!canBePrefix && !canBeSuffix && (expectingAnnotation.type() != LangKeyType.INFIX));
    }

    private void checkKeyPrefix(LangKey annotation, String prefix, Tree tree, CompilationUnitTree cut) {
        if (annotation.skipCheck()) return;
        Consumer<String> treeWarn = (String str) -> trees.printMessage(Diagnostic.Kind.WARNING, str, tree, cut);
        checkKeyPrefix(annotation, prefix, treeWarn);
    }

    private void checkKeyPrefix(LangKey annotation, String prefix, Element element) {
        if (annotation.skipCheck()) return;
        Consumer<String> envWarn = (String str) -> processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, str, element);
        checkKeyPrefix(annotation, prefix, envWarn);
    }

    private void checkKeyPrefix(LangKey annotation, String prefix, Consumer<String> warn) {
        final String[] checkedLang = annotation.value();
        if (prefix.startsWith("internal.")) {
            for (Map.Entry<String, Map<String, String>> lang : internalMap.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(checkedLang).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    warn.accept("Key component prefix " + prefix + " not found in internal lang " + lang.getKey());
                }
            }

            Set<String> notFound = new HashSet<>();
            for (Map.Entry<String, Map<String, String>> lang : internalMap.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    notFound.add(lang.getKey());
                }
            }
            if (notFound.size() != internalMap.size()) {
                notFound.forEach(langName -> warn.accept("Key component prefix " + prefix + " not found in internal lang " + langName));
            } else {
                for (Map.Entry<String, Map<String, String>> lang : additionalMap.entrySet()) {
                    if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                        continue;
                    if (lang.getValue().keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                        warn.accept("Key component prefix " + prefix + " not found in additional internal lang " + lang.getKey());
                    }
                }
            }
        } else {
            for (Map.Entry<String, Map<String, String>> lang : map.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(checkedLang).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    warn.accept("Key component prefix " + prefix + " not found in lang " + lang.getKey());
                }
            }
        }
    }

    private void checkKeyInSuffix(LangKey annotation, String fix, Element element) {
        if (annotation.skipCheck()) return;
        final String[] checkedLang = annotation.value();
        List<Set<Map.Entry<String, Map<String, String>>>> entrySets = annotation.isInternal() ? Stream.of(internalMap.entrySet(), additionalMap.entrySet()).collect(Collectors.toList()) : Stream.of(map.entrySet()).collect(Collectors.toList());
        boolean isSuffix = annotation.type() == LangKeyType.SUFFIX;
        Map<String, Boolean> chk = checkInSuffix(fix, checkedLang, entrySets, isSuffix);
        chk.forEach((k, v) -> {
            if (!v)
                processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, "Key component " + (isSuffix ? "suffix " : "infix ") + fix + " not found in lang " + k, element);
        });
    }

    private void checkKeyInSuffix(LangKey annotation, String fix, Tree tree, CompilationUnitTree cut, boolean isSuffix) {
        if (annotation.skipCheck()) return;
        List<Set<Map.Entry<String, Map<String, String>>>> entrySets = Stream.of(internalMap.entrySet(), map.entrySet(), additionalMap.entrySet()).collect(Collectors.toList());
        final String[] checkedLang = annotation.value();
        Map<String, Boolean> chk = checkInSuffix(fix, checkedLang, entrySets, isSuffix);
        chk.forEach((k, v) -> {
            if (!v)
                trees.printMessage(Diagnostic.Kind.WARNING, "Key " + (isSuffix ? "suffix " : "infix ") + fix + " not found in " + k, tree, cut);
        });
    }

    private HashBasedTable<String, String, String> checkKey(LangKey annotation, String key, Tree tree, CompilationUnitTree cut) {
        if (annotation.skipCheck()) return null;
        Consumer<String> treeWarn = (String str) -> trees.printMessage(Diagnostic.Kind.WARNING, str, tree, cut);
        return checkKey(annotation, key, treeWarn);
    }

    private HashBasedTable<String, String, String> checkKey(LangKey annotation, String key, Element element) {
        if (annotation.skipCheck()) return null;
        Consumer<String> envWarn = (String str) -> processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, str, element);
        return checkKey(annotation, key, envWarn);
    }

    private HashBasedTable<String, String, String> checkKey(LangKey annotation, String key, Consumer<String> warn) {
        HashBasedTable<String, String, String> value = HashBasedTable.create();
        if (key.startsWith("internal.")) {
            Set<String> notFound = new HashSet<>();
            for (Map.Entry<String, Map<String, String>> lang : internalMap.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().get(key) == null) {
                    notFound.add(lang.getKey());
                } else {
                    value.put(lang.getKey(), key, lang.getValue().get(key));
                }
            }
            if (additionalMap.size() == 0 || notFound.size() != internalMap.size()) {
                notFound.forEach(langName -> warn.accept("Key " + key + " not found in internal lang " + langName));
            } else {
                for (Map.Entry<String, Map<String, String>> lang : additionalMap.entrySet()) {
                    if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                        continue;
                    if (lang.getValue().get(key) == null) {
                        warn.accept("Key " + key + " not found in internal and additional internal lang " + lang.getKey());
                    } else {
                        value.put(lang.getKey(), key, lang.getValue().get(key));
                    }
                }
            }
        } else {
            for (Map.Entry<String, Map<String, String>> lang : map.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().get(key) == null) {
                    warn.accept("Key " + key + " not found in lang " + lang.getKey());
                } else {
                    value.put(lang.getKey(), key, lang.getValue().get(key));
                }
            }
        }
        return value;
    }

    private Map<String, Boolean> checkInSuffix(String fix, String[] checkedLang, List<Set<Map.Entry<String, Map<String, String>>>> entrySets, boolean isSuffix) {
        Map<String, Boolean> ret = new HashMap<>();
        for (Set<Map.Entry<String, Map<String, String>>> entrySet : entrySets) {
            for (Map.Entry<String, Map<String, String>> lang : entrySet) {
                if (checkedLang.length > 0 && Stream.of(checkedLang).noneMatch(lang.getKey()::startsWith))
                    continue;
                Set<String> allKeys = lang.getValue().keySet();
                if (isSuffix) {
                    ret.compute(lang.getKey(), (k, v) -> ((v == null) ? false : v) || allKeys.stream().anyMatch(key -> key.endsWith(fix)));
                } else {
                    ret.compute(lang.getKey(), (k, v) -> ((v == null) ? false : v) || allKeys.stream().anyMatch(key -> key.contains(fix)));
                }
            }
        }
        return ret;
    }

    @Override
    public void started(TaskEvent taskEvt) {
        //No-op
    }
}