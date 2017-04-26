package org.librazy.nyaautils_lang_checker;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.sun.source.tree.Tree.Kind.*;

@SupportedAnnotationTypes({
        "org.librazy.nyaautils_lang_checker.LangKey",
})
@SupportedOptions({
        "CLASS_OUTPUT_PATH",
        "LANG_FILE_PATH",
        "LANG_FILE_EXT",
})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class NyaaUtilsLangAnnotationProcessor extends AbstractProcessor implements Plugin, TaskListener {

    private final static Map<String, Map<String, String>> internalMap = new HashMap<>();
    private final static Map<String, Map<String, String>> map = new HashMap<>();
    private final static List<File> langFiles = new ArrayList<>();
    private static ProcessingEnvironment processingEnvironment;
    private static Trees trees;
    private static Types typeUtils;
    private static boolean internalLoaded;
    @Override
    public synchronized void init(ProcessingEnvironment processingEnvi) {
        try {
            processingEnvironment = processingEnvi;
            trees = Trees.instance(processingEnvi);
            typeUtils = processingEnvironment.getTypeUtils();
            super.init(processingEnvi);
            Map<String, String> options = processingEnv.getOptions();
            String pathRegex = options.get("CLASS_OUTPUT_PATH") == null ? "build/classes/(main/)?" : options.get("CLASS_OUTPUT_PATH");
            String langRegex = options.get("LANG_FILE_PATH") == null ? "src/main/resources/lang/" : options.get("LANG_FILE_PATH");
            String langExt = options.get("LANG_FILE_EXT") == null ? ".yml" : options.get("LANG_FILE_EXT");
            Filer filer = processingEnvi.getFiler();
            FileObject fileObject = filer.getResource(StandardLocation.CLASS_OUTPUT, "", "langChecker");
            String path = "/" + URLDecoder.decode(fileObject.toUri().toString().replaceFirst(pathRegex + "langChecker", langRegex), StandardCharsets.UTF_8.name()).replaceFirst("file:/", "");
            File f = new File(path);

            processingEnvi.getMessager().printMessage(Diagnostic.Kind.NOTE, "Lang resources path:" + f.getCanonicalPath());
            File[] files = f.listFiles(file -> file.isFile() && file.getPath().endsWith(langExt));
            if (files == null) {
                processingEnvi.getMessager().printMessage(Diagnostic.Kind.WARNING, "Lang resources not found!");
                return;
            }
            for (File file : files) {
                try {
                    processingEnvi.getMessager().printMessage(Diagnostic.Kind.NOTE, file.getCanonicalPath());
                    langFiles.add(file);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            loadInternalMap(langFiles);
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
                    Object constValue = ((VariableElement) element).getConstantValue();
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
                        switch (annotation.type()){
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


    private void loadInternalMap(List<File> files) {
        internalMap.clear();
        for (File f : files) {
            try {
                InputStream stream = new FileInputStream(f);
                ConfigurationSection section = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
                internalMap.put(f.getName(), new HashMap<>());
                loadLanguageSection(internalMap.get(f.getName()), section.getConfigurationSection("internal"), "internal.", false);
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
                loadLanguageSection(map.get(f.getName()), section, "", true);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * add all language items from section into language map recursively
     * existing items won't be overwritten
     *
     * @param section        source section
     * @param prefix         used in recursion to determine the proper prefix
     * @param ignoreInternal ignore keys prefixed with `internal'
     */
    private void loadLanguageSection(Map<String, String> map, ConfigurationSection section, String prefix, boolean ignoreInternal) {
        if (map == null || section == null || prefix == null) return;
        for (String key : section.getKeys(false)) {
            String path = prefix + key;
            if (section.isString(key)) {
                if (!map.containsKey(path) && (!ignoreInternal || !path.startsWith("internal."))) {
                    if (path.startsWith("internal.")) internalLoaded = true;
                    map.put(path, ChatColor.translateAlternateColorCodes('&', section.getString(key)));
                }
            } else if (section.isConfigurationSection(key)) {
                loadLanguageSection(map, section.getConfigurationSection(key), path + ".", ignoreInternal);
            }
        }
    }

    @Override
    public java.lang.String getName() {
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
                    /**
                     * Scan a single node.
                     * The current path is updated for the duration of the scan.
                     */
                    @Override
                    public Void scan(Tree tree, Void p) {
                        if (tree == null)
                            return null;

                        TreePath prev = path;
                        path = new TreePath(path, tree);
                        try {
                            return tree.accept(this, p);
                        } finally {
                            path = prev;
                        }
                    }

                    private TreePath path;

                    @Override
                    public Void visitCompilationUnit(CompilationUnitTree node, Void p) {
                        path = new TreePath(null, node);
                        super.visitCompilationUnit(node, p);
                        return p;
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree methodInv, Void v) {
                        try {
                            ExecutableElement method = (ExecutableElement) trees.getElement(path);
                            if (method.getParameters().stream().anyMatch(var -> var.getAnnotation(LangKey.class) != null)) {
                                List<LangKey> langKeyList = method.getParameters().stream().map(var -> var.getAnnotation(LangKey.class)).collect(Collectors.toList());
                                List<? extends ExpressionTree> rawArgumentList = methodInv.getArguments();
                                visitArgList(langKeyList, rawArgumentList, taskEvt);
                            }
                            return super.visitMethodInvocation(methodInv, v);
                        } catch (Exception e) {
                            e.printStackTrace();
                            return super.visitMethodInvocation(methodInv, v);
                        }
                    }

                    @Override
                    public Void visitNewClass(NewClassTree methodInv, Void v) {
                        try {
                            ExecutableElement method = (ExecutableElement) trees.getElement(path);
                            if (method.getParameters().stream().anyMatch(var -> var.getAnnotation(LangKey.class) != null)) {
                                List<LangKey> langKeyList = method.getParameters().stream().map(var -> var.getAnnotation(LangKey.class)).collect(Collectors.toList());
                                List<? extends ExpressionTree> rawArgumentList = methodInv.getArguments();
                                visitArgList(langKeyList, rawArgumentList, taskEvt);
                            }
                            return super.visitNewClass(methodInv, v);
                        } catch (Exception e) {
                            e.printStackTrace();
                            return super.visitNewClass(methodInv, v);
                        }
                    }
                }, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void visitArgList(List<LangKey> langKeyList, List<? extends ExpressionTree> rawArgumentList, TaskEvent taskEvt) {
        Iterator<LangKey> langKeyIterator = langKeyList.iterator();
        Iterator<? extends ExpressionTree> rawArgmuentIterator = rawArgumentList.iterator();

        while (langKeyIterator.hasNext() && rawArgmuentIterator.hasNext()) {
            LangKey annotation = langKeyIterator.next();
            ExpressionTree tree = rawArgmuentIterator.next();
            if (annotation == null) continue;

            visitExpressionTreeSuffix(taskEvt, annotation, tree, true, true);
        }
    }

    private void visitExpressionTreeSuffix(TaskEvent taskEvt, LangKey expectingAnnotation, ExpressionTree expressionTree, boolean canBePrefix, boolean canBeSuffix) {
        TreePath path = TreePath.getPath(taskEvt.getCompilationUnit(), expressionTree);
        TypeMirror typeMirror = trees.getTypeMirror(path);
        Element element = trees.getElement(path);
        Consumer<String> treesWarn = (String warn)-> trees.printMessage(Diagnostic.Kind.WARNING, warn, expressionTree, taskEvt.getCompilationUnit());

        if (element == null) {
            if (expressionTree.getKind() == STRING_LITERAL) {
                String value = (String) ((LiteralTree) expressionTree).getValue();
                if(canBePrefix && canBeSuffix) {
                    checkKey(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit());
                } else if(canBePrefix){
                    checkKeyPrefix(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit());
                } else {
                    checkKeyInSuffix(expectingAnnotation, value, expressionTree, taskEvt.getCompilationUnit(), canBeSuffix);
                }
            }else {
                if (expressionTree.getKind() == PLUS) {
                    BinaryTree bt = (BinaryTree) expressionTree;
                    ExpressionTree lo = bt.getLeftOperand();
                    ExpressionTree ro = bt.getRightOperand();
                    visitExpressionTreeSuffix(taskEvt, expectingAnnotation, lo, canBePrefix, false);
                    visitExpressionTreeSuffix(taskEvt, expectingAnnotation, ro, false, canBeSuffix);
                } else if(expressionTree.getKind() == PARENTHESIZED){
                    ParenthesizedTree pt = (ParenthesizedTree) expressionTree;
                    visitExpressionTreeSuffix(taskEvt, expectingAnnotation, pt.getExpression(), canBePrefix, canBeSuffix);
                } else if(expressionTree.getKind() == CONDITIONAL_EXPRESSION){
                    ConditionalExpressionTree ct = (ConditionalExpressionTree) expressionTree;
                    visitExpressionTreeSuffix(taskEvt, expectingAnnotation, ct.getTrueExpression(), canBePrefix, canBeSuffix);
                    visitExpressionTreeSuffix(taskEvt, expectingAnnotation, ct.getFalseExpression(), canBePrefix, canBeSuffix);
                }else {
                    treesWarn.accept("Using raw value " + expressionTree.getKind().toString().toLowerCase() +" as lang key:");
                }
            }
        } else if (element.getAnnotation(LangKey.class) == null && typeMirror.getAnnotation(LangKey.class) == null) {
            if (expressionTree.getKind() == METHOD_INVOCATION) {
                MethodInvocationTree methodInvocationTree = (MethodInvocationTree) expressionTree;
                TreePath mtPath = TreePath.getPath(taskEvt.getCompilationUnit(), methodInvocationTree);
                Element mtElement = trees.getElement(mtPath);
                TypeMirror mtType = mtElement.asType();
                if (mtElement instanceof ExecutableElement) {
                    ExecutableElement executableElement = (ExecutableElement) mtElement;
                    mtType = executableElement.getReturnType();
                }
                TypeElement mtTypeElement = (TypeElement) typeUtils.asElement(mtType);
                if (mtElement.getAnnotation(LangKey.class) == null && mtType.getAnnotation(LangKey.class) == null) {
                    ExpressionTree methodSelect = methodInvocationTree.getMethodSelect();
                    if (methodSelect instanceof MemberSelectTree && (mtElement.getSimpleName().toString().equals("name") || mtElement.getSimpleName().toString().equals("toString"))) {
                        MemberSelectTree memberSelectTree = (MemberSelectTree) methodSelect;
                        ExpressionTree receiver = memberSelectTree.getExpression();
                        TreePath rPath = TreePath.getPath(taskEvt.getCompilationUnit(), receiver);
                        Element rcElement = trees.getElement(rPath);
                        TypeMirror rcType = rcElement.asType();
                        if (rcType.getKind() == TypeKind.EXECUTABLE) {
                            ExecutableElement executableRcElement = (ExecutableElement) rcElement;
                            rcType = executableRcElement.getReturnType();
                        }
                        TypeElement rcTypeElement = (TypeElement) typeUtils.asElement(rcType);
                        if (rcTypeElement == null) {
                            treesWarn.accept("Using not annotated enum as lang key suffix:");
                        } else {
                            LangKey actualAnnotation = rcElement.getAnnotation(LangKey.class);
                            if (actualAnnotation == null) {
                                actualAnnotation = rcTypeElement.getAnnotation(LangKey.class);
                            }
                            if (actualAnnotation == null) {
                                treesWarn.accept("Using not annotated enum(-like) as lang key suffix:");
                            } else {
                                checkActualAnnotation(canBePrefix, canBeSuffix, treesWarn, actualAnnotation);
                            }
                        }
                    } else {
                        treesWarn.accept("Using not annotated method return as lang key suffix:");
                    }
                } else {
                    LangKey actualAnnotation = mtElement.getAnnotation(LangKey.class);
                    if (actualAnnotation == null) {
                        actualAnnotation = mtType.getAnnotation(LangKey.class);
                    }
                    checkActualAnnotation(canBePrefix, canBeSuffix, treesWarn, actualAnnotation);
                }
            } else {
                if (expressionTree.getKind() == IDENTIFIER || expressionTree.getKind() == MEMBER_SELECT) {
                    treesWarn.accept("Using not annotated variable as lang key:");
                } else {
                    treesWarn.accept( "Using not annotated element as lang key:");
                }
            }
        } else {
            LangKey actualAnnotation = element.getAnnotation(LangKey.class);
            if (actualAnnotation == null) {
                actualAnnotation = typeMirror.getAnnotation(LangKey.class);
            }
            if (actualAnnotation.type() != expectingAnnotation.type()) {
                if (checkExpectingAnnotation(canBePrefix, canBeSuffix, expectingAnnotation)) {
                    treesWarn.accept("Expecting " + expectingAnnotation.type().toString().toLowerCase() + ", found " + actualAnnotation.type().toString().toLowerCase() + ":");
                }
            }
        }
    }

    private void checkActualAnnotation(boolean canBePrefix, boolean canBeSuffix, Consumer<String> treesWarn, LangKey actualAnnotation) {
        if (canBePrefix && canBeSuffix && (actualAnnotation.type() != LangKeyType.KEY)) {
            treesWarn.accept("Expecting key, but found " + actualAnnotation.type().toString().toLowerCase() + " :");
        }
        if (canBePrefix && !canBeSuffix && (actualAnnotation.type() != LangKeyType.PREFIX)) {
            treesWarn.accept("Expecting prefix, but found " + actualAnnotation.type().toString().toLowerCase() + " :");
        }
        if (!canBePrefix && canBeSuffix && (actualAnnotation.type() != LangKeyType.SUFFIX)) {
            treesWarn.accept("Expecting suffix, but found " + actualAnnotation.type().toString().toLowerCase() + " :");
        }
        if (!canBePrefix && !canBeSuffix && (actualAnnotation.type() != LangKeyType.INFIX)) {
            treesWarn.accept("Expecting infix, but found " + actualAnnotation.type().toString().toLowerCase() + " :");
        }
    }

    private boolean checkExpectingAnnotation(boolean canBePrefix, boolean canBeSuffix,  LangKey expectingAnnotation) {
        if (canBePrefix && canBeSuffix && (expectingAnnotation.type() != LangKeyType.KEY)) {
            return false;
        }
        if (canBePrefix && !canBeSuffix && (expectingAnnotation.type() != LangKeyType.PREFIX)) {
            return false;
        }
        if (!canBePrefix && canBeSuffix && (expectingAnnotation.type() != LangKeyType.SUFFIX)) {
            return false;
        }
        if (!canBePrefix && !canBeSuffix && (expectingAnnotation.type() != LangKeyType.INFIX)) {
            return false;
        }
        return true;
    }

    private void checkKeyPrefix(LangKey annotation, String prefix, Tree tree, CompilationUnitTree cut) {
        final String[] checkedLang = annotation.value();
        if (prefix.startsWith("internal.")) {
            if (!internalLoaded) return;
            for (Map.Entry<String, Map<String, String>> lang : internalMap.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(checkedLang).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    trees.printMessage(Diagnostic.Kind.WARNING, "Key prefix " + prefix + " not found in internal lang " + lang.getKey(), tree, cut);
                }
            }
        } else {
            for (Map.Entry<String, Map<String, String>> lang : map.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(checkedLang).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    trees.printMessage(Diagnostic.Kind.WARNING, "Key prefix " + prefix + " not found in lang " + lang.getKey(), tree, cut);
                }
            }
        }
    }

    private void checkKeyPrefix(LangKey annotation, String prefix, Element element) {
        final String[] checkedLang = annotation.value();
        if (prefix.startsWith("internal.")) {
            if (!internalLoaded) return;
            for (Map.Entry<String, Map<String, String>> lang : internalMap.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(checkedLang).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, "Key component prefix " + prefix + " not found in internal lang " + lang.getKey(), element);
                }
            }
        } else {
            for (Map.Entry<String, Map<String, String>> lang : map.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(checkedLang).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, "Key component prefix " + prefix + " not found in lang " + lang.getKey(), element);
                }
            }
        }
    }

    private void checkKeyInSuffix(LangKey annotation, String fix, Element element) {
        final String[] checkedLang = annotation.value();
        Set<Map.Entry<String, Map<String, String>>> entrySet = annotation.isInternal() ? internalMap.entrySet() : map.entrySet();
        boolean isSuffix = annotation.type() == LangKeyType.SUFFIX;
        Map<String, Boolean> chk = checkInSuffix(fix, checkedLang, Stream.of(entrySet).collect(Collectors.toList()), isSuffix);
        chk.forEach((k, v) -> {
            if (!v)
                processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, "Key component " + (isSuffix ? "suffix " : "infix ") + fix + " not found in lang " + k, element);
        });
    }

    private void checkKeyInSuffix(LangKey annotation, String fix, Tree tree, CompilationUnitTree cut, boolean isSuffix) {
        List<Set<Map.Entry<String, Map<String, String>>>> entrySets = Stream.of(internalMap.entrySet(), map.entrySet()).collect(Collectors.toList());
        final String[] checkedLang = annotation.value();
        Map<String, Boolean> chk = checkInSuffix(fix, checkedLang, entrySets, isSuffix);
        chk.forEach((k, v) -> {
            if (!v)
                trees.printMessage(Diagnostic.Kind.WARNING, "Key suffix " + fix + " not found in" + k, tree, cut);
        });
    }

    private void checkKey(LangKey annotation, String key, Tree tree, CompilationUnitTree cut) {
        if (key.startsWith("internal.")) {
            if (!internalLoaded) return;
            for (Map.Entry<String, Map<String, String>> lang : internalMap.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().get(key) == null) {
                    trees.printMessage(Diagnostic.Kind.WARNING, "Key " + key + " not found in internal lang " + lang.getKey(), tree, cut);
                }
            }
        } else {
            for (Map.Entry<String, Map<String, String>> lang : map.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().get(key) == null) {
                    trees.printMessage(Diagnostic.Kind.WARNING, "Key " + key + " not found in lang " + lang.getKey(), tree, cut);
                }
            }
        }
    }

    private void checkKey(LangKey annotation, String key, Element element) {
        if (key.startsWith("internal.")) {
            if (!internalLoaded) return;
            for (Map.Entry<String, Map<String, String>> lang : internalMap.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().get(key) == null) {
                    processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, "Key " + key + " not found in internal lang " + lang.getKey(), element);
                }
            }
        } else {
            for (Map.Entry<String, Map<String, String>> lang : map.entrySet()) {
                if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                    continue;
                if (lang.getValue().get(key) == null) {
                    processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, "Key " + key + " not found in lang " + lang.getKey(), element);
                }
            }
        }
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