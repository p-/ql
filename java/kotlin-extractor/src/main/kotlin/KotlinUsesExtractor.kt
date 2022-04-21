package com.github.codeql

import com.github.codeql.utils.*
import com.github.codeql.utils.versions.isRawType
import com.semmle.extractor.java.OdasaOutput
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.parentsWithSelf
import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.util.OperatorNameConventions

open class KotlinUsesExtractor(
    open val logger: Logger,
    open val tw: TrapWriter,
    val dependencyCollector: OdasaOutput.TrapFileManager?,
    val externalClassExtractor: ExternalDeclExtractor,
    val primitiveTypeMapping: PrimitiveTypeMapping,
    val pluginContext: IrPluginContext,
    val globalExtensionState: KotlinExtractorGlobalState
) {
    fun usePackage(pkg: String): Label<out DbPackage> {
        return extractPackage(pkg)
    }

    fun extractPackage(pkg: String): Label<out DbPackage> {
        val pkgLabel = "@\"package;$pkg\""
        val id: Label<DbPackage> = tw.getLabelFor(pkgLabel, {
            tw.writePackages(it, pkg)
        })
        return id
    }

    fun useFileClassType(f: IrFile) = TypeResults(
        TypeResult(extractFileClass(f), "", ""),
        TypeResult(fakeKotlinType(), "", "")
    )

    fun getJvmName(container: IrAnnotationContainer): String? {
        for(a: IrConstructorCall in container.annotations) {
            val t = a.type
            if (t is IrSimpleType && a.valueArgumentsCount == 1) {
                val owner = t.classifier.owner
                val v = a.getValueArgument(0)
                if (owner is IrClass) {
                    val aPkg = owner.packageFqName?.asString()
                    val name = owner.name.asString()
                    if(aPkg == "kotlin.jvm" && name == "JvmName" && v is IrConst<*>) {
                        val value = v.value
                        if (value is String) {
                            return value
                        }
                    }
                }
            }
        }
        return null
    }

    @OptIn(kotlin.ExperimentalStdlibApi::class) // Annotation required by kotlin versions < 1.5
    fun extractFileClass(f: IrFile): Label<out DbClass> {
        val fileName = f.fileEntry.name
        val pkg = f.fqName.asString()
        val defaultName = fileName.replaceFirst(Regex(""".*[/\\]"""), "").replaceFirst(Regex("""\.kt$"""), "").replaceFirstChar({ it.uppercase() }) + "Kt"
        var jvmName = getJvmName(f) ?: defaultName
        val qualClassName = if (pkg.isEmpty()) jvmName else "$pkg.$jvmName"
        val label = "@\"class;$qualClassName\""
        val id: Label<DbClass> = tw.getLabelFor(label, {
            val fileId = tw.mkFileId(f.path, false)
            val locId = tw.getWholeFileLocation(fileId)
            val pkgId = extractPackage(pkg)
            tw.writeClasses(it, jvmName, pkgId, it)
            tw.writeFile_class(it)
            tw.writeHasLocation(it, locId)

            addModifiers(it, "public", "final")
        })
        return id
    }

    data class UseClassInstanceResult(val typeResult: TypeResult<DbClassorinterface>, val javaClass: IrClass)
    /**
     * A triple of a type's database label, its signature for use in callable signatures, and its short name for use
     * in all tables that provide a user-facing type name.
     *
     * `signature` is a Java primitive name (e.g. "int"), a fully-qualified class name ("package.OuterClass.InnerClass"),
     * or an array ("componentSignature[]")
     * Type variables have the signature of their upper bound.
     * Type arguments and anonymous types do not have a signature.
     *
     * `shortName` is a Java primitive name (e.g. "int"), a class short name with Java-style type arguments ("InnerClass<E>" or
     * "OuterClass<ConcreteArgument>" or "OtherClass<? extends Bound>") or an array ("componentShortName[]").
     */
    data class TypeResult<out LabelType>(val id: Label<out LabelType>, val signature: String?, val shortName: String) {
        fun <U> cast(): TypeResult<U> {
            @Suppress("UNCHECKED_CAST")
            return this as TypeResult<U>
        }
    }
    data class TypeResults(val javaResult: TypeResult<DbType>, val kotlinResult: TypeResult<DbKt_type>)

    fun useType(t: IrType, context: TypeContext = TypeContext.OTHER) =
        when(t) {
            is IrSimpleType -> useSimpleType(t, context)
            else -> {
                logger.error("Unrecognised IrType: " + t.javaClass)
                TypeResults(TypeResult(fakeLabel(), "unknown", "unknown"), TypeResult(fakeLabel(), "unknown", "unknown"))
            }
        }

    fun getJavaEquivalentClass(c: IrClass) =
        getJavaEquivalentClassId(c)?.let { pluginContext.referenceClass(it.asSingleFqName()) }?.owner

    /**
     * Gets a KotlinFileExtractor based on this one, except it attributes locations to the file that declares the given class.
     */
    private fun withFileOfClass(cls: IrClass): KotlinFileExtractor {
        val clsFile = cls.fileOrNull

        if (this is KotlinFileExtractor && this.filePath == clsFile?.path) {
            return this
        }

        if (clsFile == null || isExternalDeclaration(cls)) {
            val filePath = getIrClassBinaryPath(cls)
            val newTrapWriter = tw.makeFileTrapWriter(filePath, true)
            val newLoggerTrapWriter = logger.tw.makeFileTrapWriter(filePath, false)
            val newLogger = FileLogger(logger.loggerBase, newLoggerTrapWriter)
            return KotlinFileExtractor(newLogger, newTrapWriter, filePath, dependencyCollector, externalClassExtractor, primitiveTypeMapping, pluginContext, globalExtensionState)
        }

        val newTrapWriter = tw.makeSourceFileTrapWriter(clsFile, true)
        val newLoggerTrapWriter = logger.tw.makeSourceFileTrapWriter(clsFile, false)
        val newLogger = FileLogger(logger.loggerBase, newLoggerTrapWriter)
        return KotlinFileExtractor(newLogger, newTrapWriter, clsFile.path, dependencyCollector, externalClassExtractor, primitiveTypeMapping, pluginContext, globalExtensionState)
    }

    // The Kotlin compiler internal representation of Outer<T>.Inner<S>.InnerInner<R> is InnerInner<R, S, T>. This function returns just `R`.
    fun removeOuterClassTypeArgs(c: IrClass, argsIncludingOuterClasses: List<IrTypeArgument>?): List<IrTypeArgument>? {
        return argsIncludingOuterClasses?.let {
            if (it.size > c.typeParameters.size)
                it.take(c.typeParameters.size)
            else
                null
        } ?: argsIncludingOuterClasses
    }

    fun isStaticClass(c: IrClass) = c.visibility != DescriptorVisibilities.LOCAL && !c.isInner

    // Gets nested inner classes starting at `c` and proceeding outwards to the innermost enclosing static class.
    // For example, for (java syntax) `class A { static class B { class C { class D { } } } }`,
    // `nonStaticParentsWithSelf(D)` = `[D, C, B]`.
    fun parentsWithTypeParametersInScope(c: IrClass): List<IrDeclarationParent> {
        val parentsList = c.parentsWithSelf.toList()
        val firstOuterClassIdx = parentsList.indexOfFirst { it is IrClass && isStaticClass(it) }
        return if (firstOuterClassIdx == -1) parentsList else parentsList.subList(0, firstOuterClassIdx + 1)
    }

    // Gets the type parameter symbols that are in scope for class `c` in Kotlin order (i.e. for
    // `class NotInScope<T> { static class OutermostInScope<A, B> { class QueryClass<C, D> { } } }`,
    // `getTypeParametersInScope(QueryClass)` = `[C, D, A, B]`.
    fun getTypeParametersInScope(c: IrClass) =
        parentsWithTypeParametersInScope(c).mapNotNull({ getTypeParameters(it) }).flatten()

    // Returns a map from `c`'s type variables in scope to type arguments `argsIncludingOuterClasses`.
    // Hack for the time being: the substituted types are always nullable, to prevent downstream code
    // from replacing a generic parameter by a primitive. As and when we extract Kotlin types we will
    // need to track this information in more detail.
    fun makeTypeGenericSubstitutionMap(c: IrClass, argsIncludingOuterClasses: List<IrTypeArgument>) =
        getTypeParametersInScope(c).map({ it.symbol }).zip(argsIncludingOuterClasses.map { it.withQuestionMark(true) }).toMap()

    // The Kotlin compiler internal representation of Outer<A, B>.Inner<C, D>.InnerInner<E, F>.someFunction<G, H>.LocalClass<I, J> is LocalClass<I, J, G, H, E, F, C, D, A, B>. This function returns [A, B, C, D, E, F, G, H, I, J].
    fun orderTypeArgsLeftToRight(c: IrClass, argsIncludingOuterClasses: List<IrTypeArgument>?): List<IrTypeArgument>? {
        if(argsIncludingOuterClasses.isNullOrEmpty())
            return argsIncludingOuterClasses
        val ret = ArrayList<IrTypeArgument>()
        // Iterate over nested inner classes starting at `c`'s surrounding top-level or static nested class and ending at `c`, from the outermost inwards:
        val truncatedParents = parentsWithTypeParametersInScope(c)
        for(parent in truncatedParents.reversed()) {
            val parentTypeParameters = getTypeParameters(parent)
            val firstArgIdx = argsIncludingOuterClasses.size - (ret.size + parentTypeParameters.size)
            ret.addAll(argsIncludingOuterClasses.subList(firstArgIdx, firstArgIdx + parentTypeParameters.size))
        }
        return ret
    }

    // `typeArgs` can be null to describe a raw generic type.
    // For non-generic types it will be zero-length list.
    fun useClassInstance(c: IrClass, typeArgs: List<IrTypeArgument>?, inReceiverContext: Boolean = false): UseClassInstanceResult {
        if (c.isAnonymousObject) {
            logger.error("Unexpected access to anonymous class instance")
        }

        val substituteClass = getJavaEquivalentClass(c)

        val extractClass = substituteClass ?: c

        // `KFunction1<T1,T2>` is substituted by `KFunction<T>`. The last type argument is the return type.
        // References to SomeGeneric<T1, T2, ...> where SomeGeneric is declared SomeGeneric<T1, T2, ...> are extracted
        // as if they were references to the unbound type SomeGeneric.
        val extractedTypeArgs = when {
            c.symbol.isKFunction() && typeArgs != null && typeArgs.isNotEmpty() -> listOf(typeArgs.last())
            typeArgs != null && isUnspecialised(c, typeArgs) -> listOf()
            else -> typeArgs
        }

        val classTypeResult = addClassLabel(extractClass, extractedTypeArgs, inReceiverContext)

        // Extract both the Kotlin and equivalent Java classes, so that we have database entries
        // for both even if all internal references to the Kotlin type are substituted.
        if(c != extractClass) {
            extractClassLaterIfExternal(c)
        }

        return UseClassInstanceResult(classTypeResult, extractClass)
    }

    fun isArray(t: IrSimpleType) = t.isBoxedArray || t.isPrimitiveArray()

    fun extractClassLaterIfExternal(c: IrClass) {
        if (isExternalDeclaration(c)) {
            extractExternalClassLater(c)
        }
    }

    fun extractExternalEnclosingClassLater(d: IrDeclaration) {
        when (val parent = d.parent) {
            is IrClass -> extractExternalClassLater(parent)
            is IrFunction -> extractExternalEnclosingClassLater(parent)
            is IrFile -> logger.error("extractExternalEnclosingClassLater but no enclosing class.")
            else -> logger.error("Unrecognised extractExternalEnclosingClassLater: " + d.javaClass)
        }
    }

    fun extractPropertyLaterIfExternalFileMember(p: IrProperty) {
        if (isExternalFileClassMember(p)) {
            extractExternalClassLater(p.parentAsClass)
            dependencyCollector?.addDependency(p, externalClassExtractor.propertySignature)
            externalClassExtractor.extractLater(p)
        }
    }

    fun extractFieldLaterIfExternalFileMember(f: IrField) {
        if (isExternalFileClassMember(f)) {
            extractExternalClassLater(f.parentAsClass)
            dependencyCollector?.addDependency(f, externalClassExtractor.fieldSignature)
            externalClassExtractor.extractLater(f)
        }
    }

    fun extractFunctionLaterIfExternalFileMember(f: IrFunction) {
        if (isExternalFileClassMember(f)) {
            extractExternalClassLater(f.parentAsClass)
            (f as? IrSimpleFunction)?.correspondingPropertySymbol?.let {
                extractPropertyLaterIfExternalFileMember(it.owner)
                // No need to extract the function specifically, as the property's
                // getters and setters are extracted alongside it
                return
            }
            // Note we erase the parameter types before calling useType even though the signature should be the same
            // in order to prevent an infinite loop through useTypeParameter -> useDeclarationParent -> useFunction
            // -> extractFunctionLaterIfExternalFileMember, which would result for `fun <T> f(t: T) { ... }` for example.
            val ext = f.extensionReceiverParameter
            val parameters = if (ext != null) {
                listOf(ext) + f.valueParameters
            } else {
                f.valueParameters
            }

            val paramTypes = parameters.map { useType(erase(it.type)) }
            val signature = paramTypes.joinToString(separator = ",", prefix = "(", postfix = ")") { it.javaResult.signature!! }
            dependencyCollector?.addDependency(f, signature)
            externalClassExtractor.extractLater(f, signature)
        }
    }

    fun extractExternalClassLater(c: IrClass) {
        dependencyCollector?.addDependency(c)
        externalClassExtractor.extractLater(c)
    }

    fun tryReplaceAndroidSyntheticClass(c: IrClass): IrClass {
        // The Android Kotlin Extensions Gradle plugin introduces synthetic functions, fields and classes. The most
        // obvious signature is that they lack any supertype information even though they are not root classes.
        // If possible, replace them by a real version of the same class.
        if (c.superTypes.isNotEmpty() ||
            c.origin != IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB ||
            c.hasEqualFqName(FqName("java.lang.Object")))
            return c
        return globalExtensionState.syntheticToRealClassMap.getOrPut(c) {
            val result = c.fqNameWhenAvailable?.let {
                pluginContext.referenceClass(it)?.owner
            }
            if (result == null) {
                logger.warn("Failed to replace synthetic class ${c.name}")
            } else {
                logger.info("Replaced synthetic class ${c.name} with its real equivalent")
            }
            result
        } ?: c
    }

    fun tryReplaceAndroidSyntheticFunction(f: IrSimpleFunction): IrSimpleFunction {
        val parentClass = f.parent as? IrClass ?: return f
        val replacementClass = tryReplaceAndroidSyntheticClass(parentClass)
        if (replacementClass === parentClass)
            return f
        return globalExtensionState.syntheticToRealFunctionMap.getOrPut(f) {
            val result = replacementClass.declarations.find { replacementDecl ->
                replacementDecl is IrSimpleFunction && replacementDecl.name == f.name && replacementDecl.valueParameters.zip(f.valueParameters).all {
                    it.first.type == it.second.type
                }
            } as IrSimpleFunction?
            if (result == null) {
                logger.warn("Failed to replace synthetic class function ${f.name}")
            } else {
                logger.info("Replaced synthetic class function ${f.name} with its real equivalent")
            }
            result
        } ?: f
    }

    fun tryReplaceAndroidSyntheticField(f: IrField): IrField {
        val parentClass = f.parent as? IrClass ?: return f
        val replacementClass = tryReplaceAndroidSyntheticClass(parentClass)
        if (replacementClass === parentClass)
            return f
        return globalExtensionState.syntheticToRealFieldMap.getOrPut(f) {
            val result = replacementClass.declarations.find { replacementDecl ->
                replacementDecl is IrField && replacementDecl.name == f.name
            } as IrField?
            if (result == null) {
                logger.warn("Failed to replace synthetic class field ${f.name}")
            } else {
                logger.info("Replaced synthetic class field ${f.name} with its real equivalent")
            }
            result
        } ?: f
    }

    // `typeArgs` can be null to describe a raw generic type.
    // For non-generic types it will be zero-length list.
    fun addClassLabel(cBeforeReplacement: IrClass, argsIncludingOuterClasses: List<IrTypeArgument>?, inReceiverContext: Boolean = false): TypeResult<DbClassorinterface> {
        val c = tryReplaceAndroidSyntheticClass(cBeforeReplacement)
        val classLabelResult = getClassLabel(c, argsIncludingOuterClasses)

        var instanceSeenBefore = true

        val classLabel : Label<out DbClassorinterface> = tw.getLabelFor(classLabelResult.classLabel, {
            instanceSeenBefore = false

            extractClassLaterIfExternal(c)
        })

        if (argsIncludingOuterClasses == null || argsIncludingOuterClasses.isNotEmpty()) {
            // If this is a generic type instantiation or a raw type then it has no
            // source entity, so we need to extract it here
            val extractorWithCSource by lazy { this.withFileOfClass(c) }

            if (!instanceSeenBefore) {
                extractorWithCSource.extractClassInstance(c, argsIncludingOuterClasses)
            }

            if (inReceiverContext && globalExtensionState.genericSpecialisationsExtracted.add(classLabelResult.classLabel)) {
                val supertypeMode = if (argsIncludingOuterClasses == null) ExtractSupertypesMode.Raw else ExtractSupertypesMode.Specialised(argsIncludingOuterClasses)
                extractorWithCSource.extractClassSupertypes(c, classLabel, supertypeMode, true)
                extractorWithCSource.extractMemberPrototypes(c, argsIncludingOuterClasses, classLabel)
            }
        }

        return TypeResult(
            classLabel,
            c.fqNameWhenAvailable?.asString(),
            classLabelResult.shortName)
    }

    fun useAnonymousClass(c: IrClass) =
        tw.lm.anonymousTypeMapping.getOrPut(c) {
            TypeResults(
                TypeResult(tw.getFreshIdLabel<DbClass>(), "", ""),
                TypeResult(fakeKotlinType(), "TODO", "TODO")
            )
        }

    fun fakeKotlinType(): Label<out DbKt_type> {
        val fakeKotlinPackageId: Label<DbPackage> = tw.getLabelFor("@\"FakeKotlinPackage\"", {
            tw.writePackages(it, "fake.kotlin")
        })
        val fakeKotlinClassId: Label<DbClass> = tw.getLabelFor("@\"FakeKotlinClass\"", {
            tw.writeClasses(it, "FakeKotlinClass", fakeKotlinPackageId, it)
        })
        val fakeKotlinTypeId: Label<DbKt_nullable_type> = tw.getLabelFor("@\"FakeKotlinType\"", {
            tw.writeKt_nullable_types(it, fakeKotlinClassId)
        })
        return fakeKotlinTypeId
    }

    // `args` can be null to describe a raw generic type.
    // For non-generic types it will be zero-length list.
    fun useSimpleTypeClass(c: IrClass, args: List<IrTypeArgument>?, hasQuestionMark: Boolean): TypeResults {
        if (c.isAnonymousObject) {
            args?.let {
                if (it.isNotEmpty() && !isUnspecialised(c, it)) {
                    logger.error("Unexpected specialised instance of generic anonymous class")
                }
            }

            return useAnonymousClass(c)
        }

        val classInstanceResult = useClassInstance(c, args)
        val javaClassId = classInstanceResult.typeResult.id
        val kotlinQualClassName = getUnquotedClassLabel(c, args).classLabel
        val javaResult = classInstanceResult.typeResult
        val kotlinResult = if (true) TypeResult(fakeKotlinType(), "TODO", "TODO") else
            if (hasQuestionMark) {
                val kotlinSignature = "$kotlinQualClassName?" // TODO: Is this right?
                val kotlinLabel = "@\"kt_type;nullable;$kotlinQualClassName\""
                val kotlinId: Label<DbKt_nullable_type> = tw.getLabelFor(kotlinLabel, {
                    tw.writeKt_nullable_types(it, javaClassId)
                })
                TypeResult(kotlinId, kotlinSignature, "TODO")
            } else {
                val kotlinSignature = kotlinQualClassName // TODO: Is this right?
                val kotlinLabel = "@\"kt_type;notnull;$kotlinQualClassName\""
                val kotlinId: Label<DbKt_notnull_type> = tw.getLabelFor(kotlinLabel, {
                    tw.writeKt_notnull_types(it, javaClassId)
                })
                TypeResult(kotlinId, kotlinSignature, "TODO")
            }
        return TypeResults(javaResult, kotlinResult)
    }

    // Given either a primitive array or a boxed array, returns primitive arrays unchanged,
    // but returns boxed arrays with a nullable, invariant component type, with any nested arrays
    // similarly transformed. For example, Array<out Array<in E>> would become Array<Array<E?>?>
    // Array<*> will become Array<Any?>.
    fun getInvariantNullableArrayType(arrayType: IrSimpleType): IrSimpleType =
        if (arrayType.isPrimitiveArray())
            arrayType
        else {
            val componentType = arrayType.getArrayElementType(pluginContext.irBuiltIns)
            val componentTypeBroadened = when (componentType) {
                is IrSimpleType ->
                    if (isArray(componentType)) getInvariantNullableArrayType(componentType) else componentType
                else -> componentType
            }
            val unchanged =
                componentType == componentTypeBroadened &&
                        (arrayType.arguments[0] as? IrTypeProjection)?.variance == Variance.INVARIANT &&
                        componentType.isNullable()
            if (unchanged)
                arrayType
            else
                IrSimpleTypeImpl(
                    arrayType.classifier,
                    true,
                    listOf(makeTypeProjection(componentTypeBroadened, Variance.INVARIANT)),
                    listOf()
                )
        }

    fun useArrayType(arrayType: IrSimpleType, componentType: IrType, elementType: IrType, dimensions: Int, isPrimitiveArray: Boolean): TypeResults {

        // Ensure we extract Array<Int> as Integer[], not int[], for example:
        fun nullableIfNotPrimitive(type: IrType) = if (type.isPrimitiveType() && !isPrimitiveArray) type.makeNullable() else type

        val componentTypeResults = useType(nullableIfNotPrimitive(componentType))
        val elementTypeLabel = useType(nullableIfNotPrimitive(elementType)).javaResult.id

        val javaShortName = componentTypeResults.javaResult.shortName + "[]"

        val id = tw.getLabelFor<DbArray>("@\"array;$dimensions;{${elementTypeLabel}}\"") {
            tw.writeArrays(
                it,
                javaShortName,
                elementTypeLabel,
                dimensions,
                componentTypeResults.javaResult.id)

            extractClassSupertypes(arrayType.classifier.owner as IrClass, it, ExtractSupertypesMode.Specialised(arrayType.arguments))

            // array.length
            val length = tw.getLabelFor<DbField>("@\"field;{$it};length\"")
            val intTypeIds = useType(pluginContext.irBuiltIns.intType)
            tw.writeFields(length, "length", intTypeIds.javaResult.id, it, length)
            tw.writeFieldsKotlinType(length, intTypeIds.kotlinResult.id)
            addModifiers(length, "public", "final")

            // Note we will only emit one `clone()` method per Java array type, so we choose `Array<C?>` as its Kotlin
            // return type, where C is the component type with any nested arrays themselves invariant and nullable.
            val kotlinCloneReturnType = getInvariantNullableArrayType(arrayType).makeNullable()
            val kotlinCloneReturnTypeLabel = useType(kotlinCloneReturnType).kotlinResult.id

            val clone = tw.getLabelFor<DbMethod>("@\"callable;{$it}.clone(){$it}\"")
            tw.writeMethods(clone, "clone", "clone()", it, it, clone)
            tw.writeMethodsKotlinType(clone, kotlinCloneReturnTypeLabel)
            addModifiers(clone, "public")
        }

        val javaResult = TypeResult(
            id,
            componentTypeResults.javaResult.signature + "[]",
            javaShortName)

        val arrayClassResult = useSimpleTypeClass(arrayType.classifier.owner as IrClass, arrayType.arguments, arrayType.hasQuestionMark)
        return TypeResults(javaResult, arrayClassResult.kotlinResult)
    }

    enum class TypeContext {
        RETURN, GENERIC_ARGUMENT, OTHER
    }

    fun useSimpleType(s: IrSimpleType, context: TypeContext): TypeResults {
        if (s.abbreviation != null) {
            // TODO: Extract this information
        }
        // We use this when we don't actually have an IrClass for a class
        // we want to refer to
        // TODO: Eliminate the need for this if possible
        fun makeClass(pkgName: String, className: String): Label<DbClass> {
            val pkgId = extractPackage(pkgName)
            val label = "@\"class;$pkgName.$className\""
            val classId: Label<DbClass> = tw.getLabelFor(label, {
                tw.writeClasses(it, className, pkgId, it)
            })
            return classId
        }
        fun primitiveType(kotlinClass: IrClass, primitiveName: String?,
                          otherIsPrimitive: Boolean,
                          javaClass: IrClass,
                          kotlinPackageName: String, kotlinClassName: String): TypeResults {
            val javaResult = if ((context == TypeContext.RETURN || (context == TypeContext.OTHER && otherIsPrimitive)) && !s.hasQuestionMark && primitiveName != null) {
                    val label: Label<DbPrimitive> = tw.getLabelFor("@\"type;$primitiveName\"", {
                        tw.writePrimitives(it, primitiveName)
                    })
                    TypeResult(label, primitiveName, primitiveName)
                } else {
                    addClassLabel(javaClass, listOf())
                }
            val kotlinClassId = useClassInstance(kotlinClass, listOf()).typeResult.id
            val kotlinResult = if (true) TypeResult(fakeKotlinType(), "TODO", "TODO") else
                if (s.hasQuestionMark) {
                    val kotlinSignature = "$kotlinPackageName.$kotlinClassName?" // TODO: Is this right?
                    val kotlinLabel = "@\"kt_type;nullable;$kotlinPackageName.$kotlinClassName\""
                    val kotlinId: Label<DbKt_nullable_type> = tw.getLabelFor(kotlinLabel, {
                        tw.writeKt_nullable_types(it, kotlinClassId)
                    })
                    TypeResult(kotlinId, kotlinSignature, "TODO")
                } else {
                    val kotlinSignature = "$kotlinPackageName.$kotlinClassName" // TODO: Is this right?
                    val kotlinLabel = "@\"kt_type;notnull;$kotlinPackageName.$kotlinClassName\""
                    val kotlinId: Label<DbKt_notnull_type> = tw.getLabelFor(kotlinLabel, {
                        tw.writeKt_notnull_types(it, kotlinClassId)
                    })
                    TypeResult(kotlinId, kotlinSignature, "TODO")
                }
            return TypeResults(javaResult, kotlinResult)
        }

        val primitiveInfo = primitiveTypeMapping.getPrimitiveInfo(s)

        when {
            primitiveInfo != null -> return primitiveType(
                s.classifier.owner as IrClass,
                primitiveInfo.primitiveName, primitiveInfo.otherIsPrimitive,
                primitiveInfo.javaClass,
                primitiveInfo.kotlinPackageName, primitiveInfo.kotlinClassName
            )

            (s.isBoxedArray && s.arguments.isNotEmpty()) || s.isPrimitiveArray() -> {
                var dimensions = 1
                var isPrimitiveArray = s.isPrimitiveArray()
                val componentType = s.getArrayElementType(pluginContext.irBuiltIns)
                var elementType = componentType
                while (elementType.isBoxedArray || elementType.isPrimitiveArray()) {
                    dimensions++
                    if(elementType.isPrimitiveArray())
                        isPrimitiveArray = true
                    elementType = elementType.getArrayElementType(pluginContext.irBuiltIns)
                }

                return useArrayType(
                    s,
                    componentType,
                    elementType,
                    dimensions,
                    isPrimitiveArray
                )
            }

            s.classifier.owner is IrClass -> {
                val classifier: IrClassifierSymbol = s.classifier
                val cls: IrClass = classifier.owner as IrClass
                val args = if (s.isRawType()) null else s.arguments

                return useSimpleTypeClass(cls, args, s.hasQuestionMark)
            }
            s.classifier.owner is IrTypeParameter -> {
                val javaResult = useTypeParameter(s.classifier.owner as IrTypeParameter)
                val aClassId = makeClass("kotlin", "TypeParam") // TODO: Wrong
                val kotlinResult = if (true) TypeResult(fakeKotlinType(), "TODO", "TODO") else
                    if (s.hasQuestionMark) {
                        val kotlinSignature = "${javaResult.signature}?" // TODO: Wrong
                        val kotlinLabel = "@\"kt_type;nullable;type_param\"" // TODO: Wrong
                        val kotlinId: Label<DbKt_nullable_type> = tw.getLabelFor(kotlinLabel, {
                            tw.writeKt_nullable_types(it, aClassId)
                        })
                        TypeResult(kotlinId, kotlinSignature, "TODO")
                    } else {
                        val kotlinSignature = javaResult.signature // TODO: Wrong
                        val kotlinLabel = "@\"kt_type;notnull;type_param\"" // TODO: Wrong
                        val kotlinId: Label<DbKt_notnull_type> = tw.getLabelFor(kotlinLabel, {
                            tw.writeKt_notnull_types(it, aClassId)
                        })
                        TypeResult(kotlinId, kotlinSignature, "TODO")
                    }
                return TypeResults(javaResult, kotlinResult)
            }
            else -> {
                logger.error("Unrecognised IrSimpleType: " + s.javaClass + ": " + s.render())
                return TypeResults(TypeResult(fakeLabel(), "unknown", "unknown"), TypeResult(fakeLabel(), "unknown", "unknown"))
            }
        }
    }

    fun useDeclarationParent(
        // The declaration parent according to Kotlin
        dp: IrDeclarationParent,
        // Whether the type of entity whose parent this is can be a
        // top-level entity in the JVM's eyes. If so, then its parent may
        // be a file; otherwise, if dp is a file foo.kt, then the parent
        // is really the JVM class FooKt.
        canBeTopLevel: Boolean,
        classTypeArguments: List<IrTypeArgument>? = null,
        inReceiverContext: Boolean = false):
        Label<out DbElement>? =
        when(dp) {
            is IrFile ->
                if(canBeTopLevel) {
                    usePackage(dp.fqName.asString())
                } else {
                    extractFileClass(dp)
                }
            is IrClass -> if (classTypeArguments != null && !dp.isAnonymousObject) useClassInstance(dp, classTypeArguments, inReceiverContext).typeResult.id else useClassSource(dp)
            is IrFunction -> useFunction(dp)
            is IrExternalPackageFragment -> {
                // TODO
                logger.error("Unhandled IrExternalPackageFragment")
                null
            }
            else -> {
                logger.error("Unrecognised IrDeclarationParent: " + dp.javaClass)
                null
            }
        }

    private val IrDeclaration.isAnonymousFunction get() = this is IrSimpleFunction && name == SpecialNames.NO_NAME_PROVIDED

    data class FunctionNames(val nameInDB: String, val kotlinName: String)

    fun getFunctionShortName(f: IrFunction) : FunctionNames {
        if (f.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA || f.isAnonymousFunction)
            return FunctionNames(
                OperatorNameConventions.INVOKE.asString(),
                OperatorNameConventions.INVOKE.asString())
        (f as? IrSimpleFunction)?.correspondingPropertySymbol?.let {
            val propName = it.owner.name.asString()
            val getter = it.owner.getter
            val setter = it.owner.setter

            if (it.owner.parentClassOrNull?.kind == ClassKind.ANNOTATION_CLASS) {
                if (getter == null) {
                    logger.error("Expected to find a getter for a property inside an annotation class")
                    return FunctionNames(propName, propName)
                } else {
                    val jvmName = getJvmName(getter)
                    return FunctionNames(jvmName ?: propName, propName)
                }
            }

            when (f) {
                getter -> return FunctionNames(getJvmName(getter) ?: JvmAbi.getterName(propName), JvmAbi.getterName(propName))
                setter -> return FunctionNames(getJvmName(setter) ?: JvmAbi.setterName(propName), JvmAbi.setterName(propName))
                else -> {
                    logger.error(
                        "Function has a corresponding property, but is neither the getter nor the setter"
                    )
                }
            }
        }
        return FunctionNames(getJvmName(f) ?: f.name.asString(), f.name.asString())
    }

    // This excludes class type parameters that show up in (at least) constructors' typeParameters list.
    fun getFunctionTypeParameters(f: IrFunction): List<IrTypeParameter> {
        return if (f is IrConstructor) f.typeParameters else f.typeParameters.filter { it.parent == f }
    }

    fun getTypeParameters(dp: IrDeclarationParent): List<IrTypeParameter> =
        when(dp) {
            is IrClass -> dp.typeParameters
            is IrFunction -> getFunctionTypeParameters(dp)
            else -> listOf()
        }

    fun getEnclosingClass(it: IrDeclarationParent): IrClass? =
        when(it) {
            is IrClass -> it
            is IrFunction -> getEnclosingClass(it.parent)
            else -> null
        }

    /*
     * This is the normal getFunctionLabel function to use. If you want
     * to refer to the function in its source class then
     * classTypeArgsIncludingOuterClasses should be null. Otherwise, it
     * is the list of type arguments that need to be applied to its
     * enclosing classes to get the instantiation that this function is
     * in.
     */
    fun getFunctionLabel(f: IrFunction, classTypeArgsIncludingOuterClasses: List<IrTypeArgument>?) : String {
        return getFunctionLabel(f, null, classTypeArgsIncludingOuterClasses)
    }

    /*
     * There are some pairs of classes (e.g. `kotlin.Throwable` and
     * `java.lang.Throwable`) which are really just 2 different names
     * for the same class. However, we extract them as separate
     * classes. When extracting `kotlin.Throwable`'s methods, if we
     * looked up the parent ID ourselves, we would get as ID for
     * `java.lang.Throwable`, which isn't what we want. So we have to
     * allow it to be passed in.
    */
    fun getFunctionLabel(f: IrFunction, maybeParentId: Label<out DbElement>?, classTypeArgsIncludingOuterClasses: List<IrTypeArgument>?) =
        getFunctionLabel(f.parent, maybeParentId, getFunctionShortName(f).nameInDB, f.valueParameters, f.returnType, f.extensionReceiverParameter, getFunctionTypeParameters(f), classTypeArgsIncludingOuterClasses)

    /*
     * This function actually generates the label for a function.
     * Sometimes, a function is only generated by kotlinc when writing a
     * class file, so there is no corresponding `IrFunction` for it.
     * This function therefore takes all the constituent parts of a
     * function instead.
     */
    fun getFunctionLabel(
        // The parent of the function; normally f.parent.
        parent: IrDeclarationParent,
        // The ID of the function's parent, or null if we should work it out ourselves.
        maybeParentId: Label<out DbElement>?,
        // The name of the function; normally f.name.asString().
        name: String,
        // The value parameters that the functions takes; normally f.valueParameters.
        parameters: List<IrValueParameter>,
        // The return type of the function; normally f.returnType.
        returnType: IrType,
        // The extension receiver of the function, if any; normally f.extensionReceiverParameter.
        extensionReceiverParameter: IrValueParameter?,
        // The type parameters of the function. This does not include type parameters of enclosing classes.
        functionTypeParameters: List<IrTypeParameter>,
        // The type arguments of enclosing classes of the function.
        classTypeArgsIncludingOuterClasses: List<IrTypeArgument>?
    ): String {
        val parentId = maybeParentId ?: useDeclarationParent(parent, false, classTypeArgsIncludingOuterClasses, true)
        val allParams = if (extensionReceiverParameter == null) {
                            parameters
                        } else {
                            listOf(extensionReceiverParameter) + parameters
                        }

        val substitutionMap = classTypeArgsIncludingOuterClasses?.let { notNullArgs ->
            if (notNullArgs.isEmpty()) {
                null
            } else {
                val enclosingClass = getEnclosingClass(parent)
                enclosingClass?.let { notNullClass -> makeTypeGenericSubstitutionMap(notNullClass, notNullArgs) }
            }
        }
        val getIdForFunctionLabel = { it: IrValueParameter ->
            // Mimic the Java extractor's behaviour: functions with type parameters are named for their erased types;
            // those without type parameters are named for the generic type.
            val maybeSubbed = it.type.substituteTypeAndArguments(substitutionMap, TypeContext.OTHER, pluginContext)
            val maybeErased = if (functionTypeParameters.isEmpty()) maybeSubbed else erase(maybeSubbed)
            "{${useType(maybeErased).javaResult.id}}"
        }
        val paramTypeIds = allParams.joinToString(separator = ",", transform = getIdForFunctionLabel)
        val labelReturnType =
            if (name == "<init>")
                pluginContext.irBuiltIns.unitType
            else
                erase(returnType.substituteTypeAndArguments(substitutionMap, TypeContext.RETURN, pluginContext))
        val returnTypeId = useType(labelReturnType, TypeContext.RETURN).javaResult.id
        // This suffix is added to generic methods (and constructors) to match the Java extractor's behaviour.
        // Comments in that extractor indicates it didn't want the label of the callable to clash with the raw
        // method (and presumably that disambiguation is never needed when the method belongs to a parameterized
        // instance of a generic class), but as of now I don't know when the raw method would be referred to.
        val typeArgSuffix = if (functionTypeParameters.isNotEmpty() && classTypeArgsIncludingOuterClasses.isNullOrEmpty()) "<${functionTypeParameters.size}>" else "";
        return "@\"callable;{$parentId}.$name($paramTypeIds){$returnTypeId}${typeArgSuffix}\""
    }

    protected fun IrFunction.isLocalFunction(): Boolean {
        return this.visibility == DescriptorVisibilities.LOCAL
    }

    /**
     * Class to hold labels for generated classes around local functions, lambdas, function references, and property references.
     */
    open class GeneratedClassLabels(val type: TypeResults, val constructor: Label<DbConstructor>, val constructorBlock: Label<DbBlock>)

    /**
     * Class to hold labels generated for locally visible functions, such as
     *  - local functions,
     *  - lambdas, and
     *  - wrappers around function references.
     */
    class LocallyVisibleFunctionLabels(type: TypeResults, constructor: Label<DbConstructor>, constructorBlock: Label<DbBlock>, val function: Label<DbMethod>)
        : GeneratedClassLabels(type, constructor, constructorBlock)

    /**
     * Gets the labels for functions belonging to
     *  - local functions, and
     *  - lambdas.
     */
    fun getLocallyVisibleFunctionLabels(f: IrFunction): LocallyVisibleFunctionLabels {
        if (!f.isLocalFunction()){
            logger.error("Extracting a non-local function as a local one")
        }

        var res = tw.lm.locallyVisibleFunctionLabelMapping[f]
        if (res == null) {
            val javaResult = TypeResult(tw.getFreshIdLabel<DbClass>(), "", "")
            val kotlinResult = TypeResult(tw.getFreshIdLabel<DbKt_notnull_type>(), "", "")
            tw.writeKt_notnull_types(kotlinResult.id, javaResult.id)
            res = LocallyVisibleFunctionLabels(
                TypeResults(javaResult, kotlinResult),
                tw.getFreshIdLabel(),
                tw.getFreshIdLabel(),
                tw.getFreshIdLabel()
            )
            tw.lm.locallyVisibleFunctionLabelMapping[f] = res
        }

        return res
    }

    fun <T: DbCallable> useFunctionCommon(f: IrFunction, label: String): Label<out T> {
        val id: Label<T> = tw.getLabelFor(label)
        if (isExternalDeclaration(f)) {
            extractFunctionLaterIfExternalFileMember(f)
            extractExternalEnclosingClassLater(f)
        }
        return id
    }

    fun <T: DbCallable> useFunction(f: IrFunction, classTypeArgsIncludingOuterClasses: List<IrTypeArgument>? = null): Label<out T> {
        if (f.isLocalFunction()) {
            val ids = getLocallyVisibleFunctionLabels(f)
            return ids.function.cast<T>()
        } else {
            return useFunctionCommon<T>(f, getFunctionLabel(f, classTypeArgsIncludingOuterClasses))
        }
    }

    fun <T: DbCallable> useFunction(f: IrFunction, parentId: Label<out DbElement>, classTypeArgsIncludingOuterClasses: List<IrTypeArgument>?) =
        useFunctionCommon<T>(f, getFunctionLabel(f, parentId, classTypeArgsIncludingOuterClasses))

    fun getTypeArgumentLabel(
        arg: IrTypeArgument
    ): TypeResult<DbReftype> {

        fun extractBoundedWildcard(wildcardKind: Int, wildcardLabelStr: String, wildcardShortName: String, boundLabel: Label<out DbReftype>): Label<DbWildcard> =
            tw.getLabelFor(wildcardLabelStr) { wildcardLabel ->
                tw.writeWildcards(wildcardLabel, wildcardShortName, wildcardKind)
                tw.writeHasLocation(wildcardLabel, tw.unknownLocation)
                tw.getLabelFor<DbTypebound>("@\"bound;0;{$wildcardLabel}\"") {
                    tw.writeTypeBounds(it, boundLabel, 0, wildcardLabel)
                }
            }

        // Note this function doesn't return a signature because type arguments are never incorporated into function signatures.
        return when (arg) {
            is IrStarProjection -> {
                val anyTypeLabel = useType(pluginContext.irBuiltIns.anyType).javaResult.id.cast<DbReftype>()
                TypeResult(extractBoundedWildcard(1, "@\"wildcard;\"", "?", anyTypeLabel), null, "?")
            }
            is IrTypeProjection -> {
                val boundResults = useType(arg.type, TypeContext.GENERIC_ARGUMENT)
                val boundLabel = boundResults.javaResult.id.cast<DbReftype>()

                return if(arg.variance == Variance.INVARIANT)
                    boundResults.javaResult.cast<DbReftype>()
                else {
                    val keyPrefix = if (arg.variance == Variance.IN_VARIANCE) "super" else "extends"
                    val wildcardKind = if (arg.variance == Variance.IN_VARIANCE) 2 else 1
                    val wildcardShortName = "? $keyPrefix ${boundResults.javaResult.shortName}"
                    TypeResult(
                        extractBoundedWildcard(wildcardKind, "@\"wildcard;$keyPrefix{$boundLabel}\"", wildcardShortName, boundLabel),
                        null,
                        wildcardShortName)
                }
            }
            else -> {
                logger.error("Unexpected type argument.")
                return TypeResult(fakeLabel(), "unknown", "unknown")
            }
        }
    }

    data class ClassLabelResults(
        val classLabel: String, val shortName: String
    )

    /**
     * This returns the `X` in c's label `@"class;X"`.
     *
     * `argsIncludingOuterClasses` can be null to describe a raw generic type.
     * For non-generic types it will be zero-length list.
     */
    private fun getUnquotedClassLabel(c: IrClass, argsIncludingOuterClasses: List<IrTypeArgument>?): ClassLabelResults {
        val pkg = c.packageFqName?.asString() ?: ""
        val cls = c.name.asString()
        val label = when (val parent = c.parent) {
            is IrClass -> {
                "${getUnquotedClassLabel(parent, listOf()).classLabel}\$$cls"
            }
            is IrFunction -> {
                "{${useFunction<DbMethod>(parent)}}.$cls"
            }
            else -> {
                if (pkg.isEmpty()) cls else "$pkg.$cls"
            }
        }

        val reorderedArgs = orderTypeArgsLeftToRight(c, argsIncludingOuterClasses)
        val typeArgLabels = reorderedArgs?.map { getTypeArgumentLabel(it) }
        val typeArgsShortName =
            if (typeArgLabels == null)
                "<>"
            else if(typeArgLabels.isEmpty())
                ""
            else
                typeArgLabels.takeLast(c.typeParameters.size).joinToString(prefix = "<", postfix = ">", separator = ",") { it.shortName }

        return ClassLabelResults(
            label + (typeArgLabels?.joinToString(separator = "") { ";{${it.id}}" } ?: "<>"),
            cls + typeArgsShortName
        )
    }

    // `args` can be null to describe a raw generic type.
    // For non-generic types it will be zero-length list.
    fun getClassLabel(c: IrClass, argsIncludingOuterClasses: List<IrTypeArgument>?): ClassLabelResults {
        if (c.isAnonymousObject) {
            logger.error("Label generation should not be requested for an anonymous class")
        }

        val unquotedLabel = getUnquotedClassLabel(c, argsIncludingOuterClasses)
        return ClassLabelResults(
            "@\"class;${unquotedLabel.classLabel}\"",
            unquotedLabel.shortName)
    }

    fun useClassSource(c: IrClass): Label<out DbClassorinterface> {
        if (c.isAnonymousObject) {
            return useAnonymousClass(c).javaResult.id.cast<DbClass>()
        }

        // For source classes, the label doesn't include and type arguments
        val classTypeResult = addClassLabel(c, listOf())
        return classTypeResult.id
    }

    fun getTypeParameterLabel(param: IrTypeParameter): String {
        val parentLabel = useDeclarationParent(param.parent, false)
        return "@\"typevar;{$parentLabel};${param.name}\""
    }

    fun useTypeParameter(param: IrTypeParameter) =
        TypeResult(
            tw.getLabelFor<DbTypevariable>(getTypeParameterLabel(param)),
            useType(eraseTypeParameter(param)).javaResult.signature,
            param.name.asString()
        )

    fun extractModifier(m: String): Label<DbModifier> {
        val modifierLabel = "@\"modifier;$m\""
        val id: Label<DbModifier> = tw.getLabelFor(modifierLabel, {
            tw.writeModifiers(it, m)
        })
        return id
    }

    fun addModifiers(modifiable: Label<out DbModifiable>, vararg modifiers: String) =
        modifiers.forEach { tw.writeHasModifier(modifiable, extractModifier(it)) }

    sealed class ExtractSupertypesMode {
        object Unbound : ExtractSupertypesMode()
        object Raw : ExtractSupertypesMode()
        data class Specialised(val typeArgs : List<IrTypeArgument>) : ExtractSupertypesMode()
    }

    /**
     * Extracts the supertypes of class `c`, either the unbound version, raw version or a specialisation to particular
     * type arguments, depending on the value of `mode`. `id` is the label of this class or class instantiation.
     *
     * For example, for type `List` if `mode` `Specialised([String])` then we will extract the supertypes
     * of `List<String>`, i.e. `Appendable<String>` etc, or if `mode` is `Unbound` we will extract `Appendable<E>`
     * where `E` is the type variable declared as `List<E>`. Finally if `mode` is `Raw` we will extract the raw type
     * `Appendable`, represented in QL as `Appendable<>`.
     *
     * Argument `inReceiverContext` will be passed onto the `useClassInstance` invocation for each supertype.
     */
    fun extractClassSupertypes(c: IrClass, id: Label<out DbReftype>, mode: ExtractSupertypesMode = ExtractSupertypesMode.Unbound, inReceiverContext: Boolean = false) {
        extractClassSupertypes(c.superTypes, c.typeParameters, id, mode, inReceiverContext)
    }

    fun extractClassSupertypes(superTypes: List<IrType>, typeParameters: List<IrTypeParameter>, id: Label<out DbReftype>, mode: ExtractSupertypesMode = ExtractSupertypesMode.Unbound, inReceiverContext: Boolean = false) {
        // Note we only need to substitute type args here because it is illegal to directly extend a type variable.
        // (For example, we can't have `class A<E> : E`, but can have `class A<E> : Comparable<E>`)
        val subbedSupertypes = when(mode) {
            is ExtractSupertypesMode.Specialised -> {
                superTypes.map {
                    it.substituteTypeArguments(typeParameters, mode.typeArgs)
                }
            }
            else -> superTypes
        }

        for(t in subbedSupertypes) {
            when(t) {
                is IrSimpleType -> {
                    when (t.classifier.owner) {
                        is IrClass -> {
                            val classifier: IrClassifierSymbol = t.classifier
                            val tcls: IrClass = classifier.owner as IrClass
                            val typeArgs = if (t.arguments.isNotEmpty() && mode is ExtractSupertypesMode.Raw) null else t.arguments
                            val l = useClassInstance(tcls, typeArgs, inReceiverContext).typeResult.id
                            tw.writeExtendsReftype(id, l)
                        }
                        else -> {
                            logger.error("Unexpected simple type supertype: " + t.javaClass + ": " + t.render())
                        }
                    }
                } else -> {
                    logger.error("Unexpected supertype: " + t.javaClass + ": " + t.render())
                }
            }
        }
    }

    fun useValueDeclaration(d: IrValueDeclaration): Label<out DbVariable>? =
        when(d) {
            is IrValueParameter -> useValueParameter(d, null)
            is IrVariable -> useVariable(d)
            else -> {
                logger.error("Unrecognised IrValueDeclaration: " + d.javaClass)
                null
            }
        }

    /**
     * Returns `t` with generic types replaced by raw types, and type parameters replaced by their first bound.
     *
     * Note that `Array<T>` is retained (with `T` itself erased) because these are expected to be lowered to Java
     * arrays, which are not generic.
     */
    fun erase (t: IrType): IrType {
        if (t is IrSimpleType) {
            val classifier = t.classifier
            val owner = classifier.owner
            if(owner is IrTypeParameter) {
                return eraseTypeParameter(owner)
            }

            if (t.isArray() || t.isNullableArray()) {
                val elementType = t.getArrayElementType(pluginContext.irBuiltIns)
                val erasedElementType = erase(elementType)
                return withQuestionMark((classifier as IrClassSymbol).typeWith(erasedElementType), t.hasQuestionMark)
            }

            if (owner is IrClass) {
                return if (t.arguments.isNotEmpty())
                    t.addAnnotations(listOf(RawTypeAnnotation.annotationConstructor))
                else
                    t
            }
        }
        return t
    }

    fun eraseTypeParameter(t: IrTypeParameter) =
        erase(t.superTypes[0])

    /**
     * Gets the label for `vp` in the context of function instance `parent`, or in that of its declaring function if
     * `parent` is null.
     */
    fun getValueParameterLabel(vp: IrValueParameter, parent: Label<out DbCallable>?): String {
        val parentId = parent ?: useDeclarationParent(vp.parent, false)
        val idx = vp.index
        if (idx < 0) {
            val p = vp.parent
            if (p !is IrFunction || p.extensionReceiverParameter != vp) {
                // We're not extracting this and this@TYPE parameters of functions:
                logger.error("Unexpected negative index for parameter")
            }
        }
        return "@\"params;{$parentId};$idx\""
    }


    fun useValueParameter(vp: IrValueParameter, parent: Label<out DbCallable>?): Label<out DbParam> =
        tw.getLabelFor(getValueParameterLabel(vp, parent))

    fun getFieldLabel(f: IrField): String {
        val parentId = useDeclarationParent(f.parent, false)
        return "@\"field;{$parentId};${f.name.asString()}\""
    }

    fun useField(f: IrField): Label<out DbField> =
        tw.getLabelFor<DbField>(getFieldLabel(f)).also { extractFieldLaterIfExternalFileMember(f) }

    fun getPropertyLabel(p: IrProperty): String? {
        val parentId = useDeclarationParent(p.parent, false)
        if (parentId == null) {
            return null
        } else {
            return getPropertyLabel(p, parentId)
        }
    }

    fun getPropertyLabel(p: IrProperty, parentId: Label<out DbElement>) =
        "@\"property;{$parentId};${p.name.asString()}\""

    fun useProperty(p: IrProperty): Label<out DbKt_property>? {
        val label = getPropertyLabel(p)
        if (label == null) {
            return null
        } else {
            return tw.getLabelFor<DbKt_property>(label).also { extractPropertyLaterIfExternalFileMember(p) }
        }
    }

    fun useProperty(p: IrProperty, parentId: Label<out DbElement>): Label<out DbKt_property> =
        tw.getLabelFor<DbKt_property>(getPropertyLabel(p, parentId)).also { extractPropertyLaterIfExternalFileMember(p) }

    fun getEnumEntryLabel(ee: IrEnumEntry): String {
        val parentId = useDeclarationParent(ee.parent, false)
        return "@\"field;{$parentId};${ee.name.asString()}\""
    }

    fun useEnumEntry(ee: IrEnumEntry): Label<out DbField> =
        tw.getLabelFor(getEnumEntryLabel(ee))

    private fun getTypeAliasLabel(ta: IrTypeAlias): String {
        val parentId = useDeclarationParent(ta.parent, true)
        return "@\"type_alias;{$parentId};${ta.name.asString()}\""
    }

    fun useTypeAlias(ta: IrTypeAlias): Label<out DbKt_type_alias> =
        tw.getLabelFor(getTypeAliasLabel(ta))

    fun useVariable(v: IrVariable): Label<out DbLocalvar> {
        return tw.getVariableLabelFor<DbLocalvar>(v)
    }

    fun withQuestionMark(t: IrType, hasQuestionMark: Boolean) = if(hasQuestionMark) t.makeNullable() else t.makeNotNull()

}
