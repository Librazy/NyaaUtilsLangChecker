package org.librazy.nyaautils_lang_checker;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.sun.source.tree.Tree.Kind.METHOD_INVOCATION;
import static com.sun.source.tree.Tree.Kind.STRING_LITERAL;

@SupportedAnnotationTypes({"org.librazy.nyaautils_lang_checker.LangKey"})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class NyaaUtilsLangAnnotationProcessor extends AbstractProcessor implements Plugin, TaskListener {

    private final static Map<String, Map<String, String>> internalMap = new HashMap<>();
    private final static Map<String, Map<String, String>> map = new HashMap<>();
    private final static List<File> langFiles = new ArrayList<>();
    private static ProcessingEnvironment processingEnvironment;
    private static Trees trees;
    private static boolean internalLoaded;
    @Override
    public synchronized void init(ProcessingEnvironment processingEnvi) {
        try {
            processingEnvironment = processingEnvi;
            trees = Trees.instance(processingEnvi);
            super.init(processingEnvi);
            Filer filer = processingEnvi.getFiler();
            FileObject fileObject = filer.getResource(StandardLocation.CLASS_OUTPUT, "", "langChecker");
            String path = URLDecoder.decode(fileObject.toUri().toString(), StandardCharsets.UTF_8.name()).replaceAll("file:/", "").replaceAll("classes[\\\\/]main[\\\\/]langChecker", "resources/main/lang");
            File f = new File(path);
            processingEnvi.getMessager().printMessage(Diagnostic.Kind.NOTE, "Lang resources path:" + f.getCanonicalPath());
            File[] files = f.listFiles(file -> file.isFile() && file.getPath().endsWith(".yml"));
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
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree methodInv, Void v) {
                        ExecutableElement method = (ExecutableElement) TreeInfo.symbol((JCTree) methodInv.getMethodSelect());
                        if (method.getParameters().stream().anyMatch(var -> var.getAnnotation(LangKey.class) != null)) {

                            List<LangKey> langKeyList = method.getParameters().stream().map(var -> var.getAnnotation(LangKey.class)).collect(Collectors.toList());
                            List<? extends ExpressionTree> rawArgumentList = methodInv.getArguments();
                            List<Symbol> argmuentList = rawArgumentList.stream().map(arg -> TreeInfo.symbol((JCTree) arg)).collect(Collectors.toList());
                            Iterator<LangKey> langKeyIterator = langKeyList.iterator();
                            Iterator<Symbol> argmuentIterator = argmuentList.iterator();
                            Iterator<? extends ExpressionTree> rawArgmuentIterator = rawArgumentList.iterator();

                            while (langKeyIterator.hasNext() && argmuentIterator.hasNext()) {
                                LangKey annotation = langKeyIterator.next();
                                Symbol symbol = argmuentIterator.next();
                                ExpressionTree tree = rawArgmuentIterator.next();
                                if (annotation != null && symbol == null) {
                                    if (tree.getKind() == STRING_LITERAL) {
                                        String key = (String) ((LiteralTree) tree).getValue();
                                        checkKey(annotation, key, tree, taskEvt.getCompilationUnit());
                                    } else if (tree.getKind() == METHOD_INVOCATION) {
                                        MethodInvocationTree mt = (MethodInvocationTree) tree;
                                        ExecutableElement mtMethod = (ExecutableElement) TreeInfo.symbol((JCTree) mt.getMethodSelect());
                                        if (mtMethod.getAnnotation(LangKey.class) == null) {
                                            trees.printMessage(Diagnostic.Kind.WARNING, "Using not annotated method return value as lang key:", mt, taskEvt.getCompilationUnit());
                                        }
                                    }
                                } else if (annotation != null) {
                                    if (symbol.getAnnotation(LangKey.class) == null) {
                                        processingEnvironment.getMessager().printMessage(Diagnostic.Kind.WARNING, "Using not annotated variable as lang key:", symbol);
                                    }
                                }
                            }

                        }
                        return super.visitMethodInvocation(methodInv, v);
                    }
                }, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    @Override
    public void started(TaskEvent taskEvt) {
        //No-op
    }
}