package adt;


import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SupportedAnnotationTypes({"adt.Adt", "adt.AdtFields", "adt.NameAndType"})
public class AdtProcessor extends AbstractProcessor {
    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Stream<TypeElement> enums = roundEnv.getElementsAnnotatedWith(Adt.class).
                stream().filter(e -> e.getKind() == ElementKind.ENUM).map(e -> (TypeElement) e);

        enums.forEach(e -> {
            try {
                validate(e);
            } catch (ProcessorException e1) {
                messager.printMessage(Diagnostic.Kind.ERROR, e1.getMessage(), e);
                return;
            }

            Adt annotation = e.getAnnotation(Adt.class);

            List<VariableElement> constants = e.getEnclosedElements().
                    stream().filter(el1 -> el1.getKind() == ElementKind.ENUM_CONSTANT)
                    .map(el1 -> (VariableElement)el1).collect(Collectors.toList());

            TypeSpec.Builder baseBuilder = TypeSpec.classBuilder(annotation.baseName());
            baseBuilder.addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC);
            baseBuilder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
            ClassName superclass = ClassName.get(annotation.packageName(), annotation.baseName());

            TypeVariableName a = TypeVariableName.get("A");
            MethodSpec.Builder foldBuilder = MethodSpec.methodBuilder("fold")
                    .addModifiers(Modifier.PUBLIC)
                    .addTypeVariable(a).returns(a);

            for (VariableElement c : constants) {
                ClassName nested = superclass.nestedClass(c.toString());
                NameAndType[] fields = c.getAnnotation(AdtFields.class).value();
                if (fields.length == 0) {
                    TypeName typeName = ParameterizedTypeName.get(ClassName.get(Supplier.class), a);
                    foldBuilder.addParameter(ParameterSpec.builder(typeName, c.toString() + "F").build());
                }
                else {
                    TypeName typeName = ParameterizedTypeName.get(ClassName.get(Function.class), nested, a);
                    foldBuilder.addParameter(ParameterSpec.builder(typeName, c.toString() + "F").build());
                }
            }

            baseBuilder.addMethod(foldBuilder.build().toBuilder().addModifiers(Modifier.ABSTRACT).build());

            List<TypeSpec> types = constants.stream().map(c -> {
                NameAndType[] fields = c.getAnnotation(AdtFields.class).value();
                String code = fields.length == 0 ? "get()" : "apply(this)";
                ClassName nested = superclass.nestedClass(c.toString());

                MethodSpec.Builder builder = foldBuilder.build().toBuilder().
                        addStatement("return $LF.$L", c.toString(), code);

                TypeSpec.Builder adtBuilder = TypeSpec.classBuilder(c.toString()).superclass(superclass);

                MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
                MethodSpec.Builder factory = MethodSpec.methodBuilder(lowerCaseFirst(c.toString())).returns(nested);
                StringBuilder factoryCode = new StringBuilder("return new $T(");

                for (int i = 0; i < fields.length; i++) {
                    NameAndType field = fields[i];
                    TypeName type = getTypeFrom(field::type);
                    adtBuilder.addField(type, field.name(), Modifier.PUBLIC, Modifier.FINAL);
                    constructor.addParameter(type, field.name());
                    constructor.addStatement("this.$L = $L", field.name(), field.name());
                    factory.addParameter(type, field.name());
                    factoryCode.append(field.name());
                    if (i < (fields.length - 1)) {
                        factoryCode.append(",");
                    }
                }
                FieldSpec event = FieldSpec.builder(
                        ClassName.get(e),
                        e.getSimpleName().toString().toLowerCase(),
                        Modifier.PUBLIC,
                        Modifier.FINAL
                ).initializer("$T.$L", ClassName.get(e), c.toString()).build();

                adtBuilder.addField(event);
                factory.addStatement(factoryCode.append(")").toString(), nested);
                baseBuilder.addMethod(factory.addModifiers(Modifier.PUBLIC, Modifier.STATIC).build());

                return adtBuilder
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addMethod(constructor.build())
                        .addMethod(builder.build())
                        .build();
            }).collect(Collectors.toList());

            TypeSpec outerTypeSpec = baseBuilder.addTypes(types).build();
            JavaFile file = JavaFile
                    .builder(annotation.packageName(), outerTypeSpec)
                    .skipJavaLangImports(true).build();
            System.out.println("outer class\n" + file.toString());
            try {
                file.writeTo(filer);
            } catch (IOException e1) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Failed generating classes");
            }
        });

        return true;
    }

    private String lowerCaseFirst(String s) {
        return s.substring(0, 1).toLowerCase() + s.substring(1);
    }

    private TypeName getTypeFrom(Supplier<Class<?>> p) {
        try {
            p.get();
        } catch (MirroredTypeException e1) {
            return ClassName.get(e1.getTypeMirror());
        }
        throw new IllegalStateException("Not possible to get type mirror from class");
    }

    private void validate(TypeElement e) throws ProcessorException {
        if (!e.getModifiers().contains(Modifier.PUBLIC)) {
            throw new ProcessorException(e, "The class %s is not public.", e.getQualifiedName().toString());
        }
        if (e.getModifiers().contains(Modifier.ABSTRACT)) {
            throw new ProcessorException(e, "The class %s is abstract, Cannot instantate.", e.getQualifiedName().toString());
        }
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }
}

class ProcessorException extends Exception {
    Element element;

    public ProcessorException(Element element, String msg, Object... args) {
        super(String.format(msg, args));
        this.element = element;
    }

    public Element getElement() {
        return element;
    }
}
