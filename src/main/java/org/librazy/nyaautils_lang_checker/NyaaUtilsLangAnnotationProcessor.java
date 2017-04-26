package org.librazy.nyaautils_lang_checker;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.sun.source.tree.Tree.Kind.*;

@SupportedAnnotationTypes({
        "org.librazy.nyaautils_lang_checker.LangKey",
        "org.librazy.nyaautils_lang_checker.LangKeyComponent",
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
                    checkKey(annotation, key, element);
                } else if (element instanceof TypeElement) {
                    LangKey annotation = element.getAnnotation(LangKey.class);
                    TypeElement te = ((TypeElement) element);
                    if (te.getKind() == ElementKind.ENUM) {
                        List<VariableElement> variableElements =
                                te.getEnclosedElements()
                                  .stream()
                                  .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                                  .map(e -> (VariableElement) e).collect(Collectors.toList());
                        variableElements.forEach(e -> checkKey(annotation, e.toString(), element));
                    }
                }
            }
            for (final Element element : roundEnv.getElementsAnnotatedWith(LangKeyComponent.class)) {
                if (element instanceof VariableElement) {
                    LangKeyComponent annotation = element.getAnnotation(LangKeyComponent.class);
                    Object constValue = ((VariableElement) element).getConstantValue();
                    if (constValue == null || !(constValue instanceof String)) continue;
                    String key = (String) constValue;
                    checkKeyInSuffix(annotation, key, element);
                } else if (element instanceof TypeElement) {
                    LangKeyComponent annotation = element.getAnnotation(LangKeyComponent.class);
                    TypeElement te = ((TypeElement) element);
                    if (te.getKind() == ElementKind.ENUM) {
                        List<VariableElement> variableElements =
                                te.getEnclosedElements()
                                  .stream()
                                  .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                                  .map(e -> (VariableElement) e).collect(Collectors.toList());
                        if (annotation.type() == LangKeyComponentType.PREFIX) {
                            variableElements.forEach(e -> checkKeyPrefix(annotation, e.toString(), element));
                        } else {
                            variableElements.forEach(e -> checkKeyInSuffix(annotation, e.toString(), element));
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
                taskEvt.getCompilationUnit().accept(new TreePathScanner<Void, Void>() {
                    /**
                     * Returns the current path for the node, as built up by the currently
                     * active set of scan calls.
                     * @return the current path
                     */
                    public TreePath getCurrentPath() {
                        return path;
                    }

                    private TreePath path;

                    /**
                     * Scans a tree from a position identified by a TreePath.
                     * @param path the path identifying the node to be scanned
                     * @param p a parameter value passed to visit methods
                     * @return the result value from the visit method
                     */
                    public Void scan(TreePath path, Void p) {
                        this.path = path;
                        try {
                            return path.getLeaf().accept(this, p);
                        } finally {
                            this.path = null;
                        }
                    }

                    /**
                     * Scans a single node.
                     * The current path is updated for the duration of the scan.
                     *
                     * @apiNote This method should normally only be called by the
                     * scanner's {@code visit} methods, as part of an ongoing scan
                     * initiated by {@link #scan(TreePath,Object) scan(TreePath, P)}.
                     * The one exception is that it may also be called to initiate
                     * a full scan of a {@link CompilationUnitTree}.
                     *
                     * @return the result value from the visit method
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

                    @Override
                    public Void visitCompilationUnit(CompilationUnitTree node, Void p) {
                        path = new TreePath(null, node);
                        super.visitCompilationUnit(node, p);
                        return p;
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree methodInv, Void v) {
                        try {
                            ExecutableElement method = (ExecutableElement) trees.getElement(getCurrentPath());
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
                            ExecutableElement method = (ExecutableElement) trees.getElement(getCurrentPath());
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
        List<TreePath> argmuentPathList = rawArgumentList.stream().map(arg -> TreePath.getPath(taskEvt.getCompilationUnit(), arg)).collect(Collectors.toList());
        List<TypeMirror> argmuentTypeList = argmuentPathList.stream().map(argp -> trees.getTypeMirror(argp)).collect(Collectors.toList());
        List<Element> argmuentElementList = argmuentPathList.stream().map(argp -> trees.getElement(argp)).collect(Collectors.toList());


        Iterator<LangKey> langKeyIterator = langKeyList.iterator();
        Iterator<? extends ExpressionTree> rawArgmuentIterator = rawArgumentList.iterator();
        Iterator<TreePath> argmuentPathIterator = argmuentPathList.iterator();
        Iterator<TypeMirror> argmuentTypeIterator = argmuentTypeList.iterator();
        Iterator<Element> argmuentElementIterator = argmuentElementList.iterator();

        while (langKeyIterator.hasNext() && argmuentPathIterator.hasNext()) {
            LangKey annotation = langKeyIterator.next();
            TreePath path = argmuentPathIterator.next();
            TypeMirror type = argmuentTypeIterator.next();
            Element element = argmuentElementIterator.next();
            ExpressionTree tree = rawArgmuentIterator.next();
            if (annotation == null) continue;

            if (element == null) {
                if (tree.getKind() == STRING_LITERAL) {
                    String key = (String) ((LiteralTree) tree).getValue();
                    checkKey(annotation, key, tree, taskEvt.getCompilationUnit());
                } else {
                    if (tree.getKind() == PLUS) {
                        BinaryTree bt = (BinaryTree) tree;
                        ExpressionTree lo = bt.getLeftOperand();
                        ExpressionTree ro = bt.getRightOperand();

                        TreePath loPath = TreePath.getPath(taskEvt.getCompilationUnit(), lo);
                        TypeMirror loType = trees.getTypeMirror(loPath);
                        Element loElement = trees.getElement(loPath);

                        TreePath roPath = TreePath.getPath(taskEvt.getCompilationUnit(), ro);
                        TypeMirror roType = trees.getTypeMirror(roPath);
                        Element roElement = trees.getElement(roPath);

                        if (lo.getKind() == STRING_LITERAL) {
                            String prefix = (String) ((LiteralTree) lo).getValue();
                            checkKeyPrefix(annotation, prefix, tree, taskEvt.getCompilationUnit());
                        } else if (loElement != null && loElement.getAnnotation(LangKeyComponent.class) == null && loType.getAnnotation(LangKeyComponent.class) == null) {
                            trees.printMessage(Diagnostic.Kind.WARNING, "Using not annotated element as lang key prefix:", lo, taskEvt.getCompilationUnit());
                        } else if (loElement != null) {
                            LangKeyComponent anno = loElement.getAnnotation(LangKeyComponent.class);
                            if (anno == null) {
                                anno = loType.getAnnotation(LangKeyComponent.class);
                            }
                            if (anno.type() != LangKeyComponentType.SUFFIX) {
                                trees.printMessage(Diagnostic.Kind.WARNING, "Using element annotated with LangKeyComponent." + anno.type() + "as lang key prefix:", lo, taskEvt.getCompilationUnit());
                            }
                        }

                        if (ro.getKind() == STRING_LITERAL) {
                            String suffix = (String) ((LiteralTree) ro).getValue();
                            checkKeySuffix(annotation, suffix, tree, taskEvt.getCompilationUnit());
                        } else if (roElement != null && roElement.getAnnotation(LangKeyComponent.class) == null && roType.getAnnotation(LangKeyComponent.class) == null) {
                            if (ro.getKind() == METHOD_INVOCATION && roElement.getEnclosingElement() != null && roElement.getEnclosingElement().getKind() == ElementKind.CLASS) {
                                MethodInvocationTree mt = (MethodInvocationTree) ro;
                                TreePath mtPath = TreePath.getPath(taskEvt.getCompilationUnit(), ((MemberSelectTree) mt.getMethodSelect()).getExpression());
                                Element mtElement = trees.getElement(mtPath);
                                TypeMirror tm = mtElement.asType();
                                if (mtElement instanceof ExecutableElement) {
                                    ExecutableElement mexe = (ExecutableElement) mtElement;
                                    tm = mexe.getReturnType();
                                }
                                if (mtElement.getAnnotation(LangKeyComponent.class) == null && (typeUtils.asElement(tm) == null || typeUtils.asElement(tm).getAnnotation(LangKeyComponent.class) == null)) {
                                    trees.printMessage(Diagnostic.Kind.WARNING, "Using not annotated enum as lang key suffix:", ro, taskEvt.getCompilationUnit());
                                }
                            } else {
                                trees.printMessage(Diagnostic.Kind.WARNING, "Using not annotated element as lang key suffix:", ro, taskEvt.getCompilationUnit());
                            }
                        } else if (roElement != null) {
                            LangKeyComponent anno = roElement.getAnnotation(LangKeyComponent.class);
                            if (anno == null) {
                                anno = roType.getAnnotation(LangKeyComponent.class);
                            }
                            if (anno.type() != LangKeyComponentType.SUFFIX) {
                                trees.printMessage(Diagnostic.Kind.WARNING, "Using element annotated with LangKeyComponent." + anno.type() + " as lang key suffix:", ro, taskEvt.getCompilationUnit());
                            }
                        }
                    } else {
                        trees.printMessage(Diagnostic.Kind.WARNING, "Using raw value as lang key:", path.getLeaf(), taskEvt.getCompilationUnit());
                    }
                }
            } else {
                if (element.getAnnotation(LangKey.class) == null && type.getAnnotation(LangKey.class) == null) {
                    if (tree.getKind() == IDENTIFIER || tree.getKind() == MEMBER_SELECT) {
                        trees.printMessage(Diagnostic.Kind.WARNING, "Using not annotated variable as lang key:", tree, taskEvt.getCompilationUnit());
                    } else if (tree.getKind() == METHOD_INVOCATION) {
                        trees.printMessage(Diagnostic.Kind.WARNING, "Using not annotated method return value as lang key:", tree, taskEvt.getCompilationUnit());
                    } else {
                        trees.printMessage(Diagnostic.Kind.WARNING, "Using not annotated element as lang key:", tree, taskEvt.getCompilationUnit());
                    }
                }
            }
        }
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

    private void checkKeyPrefix(LangKeyComponent annotation, String prefix, Element element) {
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

    private void checkKeyInSuffix(LangKeyComponent annotation, String fix, Element element) {
        final String[] checkedLang = annotation.value();
        Set<Map.Entry<String, Map<String, String>>> entrySet = annotation.isInternal() ? internalMap.entrySet() : map.entrySet();
        boolean isSuffix = annotation.type() == LangKeyComponentType.SUFFIX;
        Map<String, Boolean> chk = checkInSuffix(fix, checkedLang, Stream.of(entrySet).collect(Collectors.toList()), isSuffix);
        chk.forEach((k, v) -> {
            if (!v)
                processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, "Key component " + (isSuffix ? "suffix " : "infix ") + fix + " not found in lang " + k, element);
        });
    }

    private void checkKeySuffix(LangKey annotation, String fix, Tree tree, CompilationUnitTree cut) {
        List<Set<Map.Entry<String, Map<String, String>>>> entrySets = Stream.of(internalMap.entrySet(), map.entrySet()).collect(Collectors.toList());
        final String[] checkedLang = annotation.value();
        Map<String, Boolean> chk = checkInSuffix(fix, checkedLang, entrySets, true);
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