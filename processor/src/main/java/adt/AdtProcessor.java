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

            MethodSpec fold = foldSpec(constants, superclass);

            baseBuilder.addMethod(fold.toBuilder().addModifiers(Modifier.ABSTRACT).build());

            List<TypeSpec> types = constants.stream().map(c -> {
                String constant = c.toString();
                NameAndType[] fields = c.getAnnotation(AdtFields.class).value();
                ClassName nested = superclass.nestedClass(constant);
                TypeSpec spec = createTypeSpecForADT(superclass, fold, constant, fields);
                createFactoryForAdtClass(baseBuilder, constant, fields, nested);
                return spec;
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

    private TypeSpec createTypeSpecForADT(ClassName superclass, MethodSpec fold, String constant, NameAndType[] fields) {
        String code = fields.length == 0 ? "get()" : "apply(this)";

        MethodSpec.Builder builder = fold.toBuilder().
                addStatement("return $LF.$L", constant, code);

        TypeSpec.Builder adtBuilder = TypeSpec.classBuilder(constant).superclass(superclass);

        MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);

        CodeBlock.Builder equalsBlock = CodeBlock.builder();
        CodeBlock.Builder hashCodeBlock = CodeBlock.builder();
        hashCodeBlock.addStatement("$T result = $L", TypeName.INT, 31);
        equalsBlock.addStatement("if (this == o) return true");
        equalsBlock.addStatement("if (o == null || getClass() != o.getClass()) return false");
        equalsBlock.add("\n");
        ClassName currentType = superclass.nestedClass(constant);
        if (fields.length == 0) {
            equalsBlock.add("return true");
        }
        else {
            equalsBlock.addStatement("$T that = ($T) o", currentType, currentType);
            equalsBlock.add("return ");
        }

        for (int i = 0; i < fields.length; i++) {
            NameAndType field = fields[i];
            TypeName type = getTypeFrom(field::type);
            adtBuilder.addField(type, field.name(), Modifier.PUBLIC, Modifier.FINAL);
            constructor.addParameter(type, field.name());
            constructor.addStatement("this.$L = $L", field.name(), field.name());
            hashCodeBlock.addStatement(
                    "$L += $T.hashCode(this.$L)",
                    "result",
                    ClassName.get(Objects.class),
                    field.name()
            );
            equalsBlock.add("$T.equals(this.$L, that.$L)", ClassName.get(Objects.class), field.name(), field.name());
            if (i < (fields.length - 1)) {
                equalsBlock.add(" && ");
            }
        }

        equalsBlock.add(";\n");
        hashCodeBlock.addStatement("return result");

        return adtBuilder
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addMethod(constructor.build())
                .addMethod(builder.build())
                .addMethod(MethodSpec.methodBuilder("hashCode")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(TypeName.INT)
                        .addCode(hashCodeBlock.build())
                        .build()
                ).addMethod(MethodSpec.methodBuilder("equals")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(TypeName.BOOLEAN)
                        .addParameter(ClassName.OBJECT, "o")
                        .addCode(equalsBlock.build())
                        .build()
                ).build();
    }

    private void createFactoryForAdtClass(TypeSpec.Builder baseBuilder, String constant, NameAndType[] fields, ClassName nested) {
        StringBuilder factoryCode = new StringBuilder("return new $T(");
        MethodSpec.Builder factory = MethodSpec.methodBuilder(lowerCaseFirst(constant)).returns(nested);

        for (int i = 0; i < fields.length; i++) {
            NameAndType field = fields[i];
            TypeName type = getTypeFrom(field::type);
            factory.addParameter(type, field.name());
            factoryCode.append(field.name());
            if (i < (fields.length - 1)) {
                factoryCode.append(",");
            }
        }

        factory.addStatement(factoryCode.append(")").toString(), nested);
        baseBuilder.addMethod(factory.addModifiers(Modifier.PUBLIC, Modifier.STATIC).build());
    }

    private MethodSpec foldSpec(List<VariableElement> constants, ClassName superclass) {
        TypeVariableName a = TypeVariableName.get("A");
        MethodSpec.Builder foldBuilder = MethodSpec.methodBuilder("fold")
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(a).returns(a);

        for (VariableElement c : constants) {
            String constant = c.toString();
            ClassName nested = superclass.nestedClass(constant);
            NameAndType[] fields = c.getAnnotation(AdtFields.class).value();
            if (fields.length == 0) {
                TypeName typeName = ParameterizedTypeName.get(ClassName.get(Supplier.class), a);
                foldBuilder.addParameter(ParameterSpec.builder(typeName, constant + "F").build());
            }
            else {
                TypeName typeName = ParameterizedTypeName.get(ClassName.get(Function.class), nested, a);
                foldBuilder.addParameter(ParameterSpec.builder(typeName, constant + "F").build());
            }
        }

        return foldBuilder.build();
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ProcessorException that = (ProcessorException) o;

        return !(element != null ? !element.equals(that.element) : that.element != null);

    }

    @Override
    public int hashCode() {
        return element != null ? element.hashCode() : 0;
    }
}
