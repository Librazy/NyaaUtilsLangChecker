package org.librazy.nyaautils_lang_checker;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.*;
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
import java.util.stream.Stream;

@SupportedAnnotationTypes({"org.librazy.nyaautils_lang_checker.LangKey"})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class NyaaUtilsLangAnnotationProcessor extends AbstractProcessor {
    private final static Map<String, Map<String, String>> internalMap = new HashMap<>();
    private final Map<String, Map<String, String>> map = new HashMap<>();
    private final List<File> langFiles = new ArrayList<>();
    Trees trees;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        Filer filer = processingEnv.getFiler();
        try {
            FileObject fileObject = filer.getResource(StandardLocation.CLASS_OUTPUT, "", "langChecker");
            String path = URLDecoder.decode(fileObject.toUri().toString(), StandardCharsets.UTF_8.name()).replaceAll("file:/", "").replaceAll("classes[\\\\/]main[\\\\/]langChecker", "resources/main/lang");
            File f = new File(path);
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Lang resources path:" + f.getCanonicalPath());
            File[] files = f.listFiles(file -> file.isFile() && file.getPath().endsWith(".yml"));
            if (files == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "Lang resources not found!");
                return;
            }
            for (File file : files) {
                try {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, file.getCanonicalPath());
                    langFiles.add(file);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
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
                    if (key.startsWith("internal.")) {
                        for (Map.Entry<String, Map<String, String>> lang : internalMap.entrySet()) {
                            if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                                continue;
                            if (lang.getValue().get(key) == null) {
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "Key " + key + " not found in internal lang " + lang.getKey());
                            }
                        }
                    } else {
                        for (Map.Entry<String, Map<String, String>> lang : map.entrySet()) {
                            if (annotation.value().length > 0 && Stream.of(annotation.value()).noneMatch(lang.getKey()::startsWith))
                                continue;
                            if (lang.getValue().get(key) == null) {
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "Key " + key + " not found in lang " + lang.getKey());
                            }
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
                    map.put(path, ChatColor.translateAlternateColorCodes('&', section.getString(key)));
                }
            } else if (section.isConfigurationSection(key)) {
                loadLanguageSection(map, section.getConfigurationSection(key), path + ".", ignoreInternal);
            }
        }
    }
}