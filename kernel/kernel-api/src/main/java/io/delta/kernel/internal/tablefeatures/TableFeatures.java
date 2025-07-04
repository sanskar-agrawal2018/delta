/*
 * Copyright (2024) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.delta.kernel.internal.tablefeatures;

import static io.delta.kernel.internal.DeltaErrors.*;
import static io.delta.kernel.internal.util.ColumnMapping.ColumnMappingMode.NONE;
import static io.delta.kernel.types.TimestampNTZType.TIMESTAMP_NTZ;
import static io.delta.kernel.types.VariantType.VARIANT;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.internal.DeltaErrors;
import io.delta.kernel.internal.TableConfig;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.util.CaseInsensitiveMap;
import io.delta.kernel.internal.util.SchemaIterable;
import io.delta.kernel.internal.util.Tuple2;
import io.delta.kernel.types.*;
import java.util.*;
import java.util.stream.Stream;

/** Contains utility methods related to the Delta table feature support in protocol. */
public class TableFeatures {

  /**
   * The prefix for setting an override of a feature option in {@linkplain Metadata} configuration.
   *
   * <p>Keys with this prefix should never be persisted in the Metadata action. The keys can be
   * filtered out by using {@linkplain #extractFeaturePropertyOverrides}.
   *
   * <p>These overrides only support add the feature as supported in the Protocol action.
   *
   * <p>Disabling features via this method is unsupported.
   */
  public static String SET_TABLE_FEATURE_SUPPORTED_PREFIX = "delta.feature.";

  /////////////////////////////////////////////////////////////////////////////////
  /// START: Define the {@link TableFeature}s                                   ///
  /// If feature instance variable ends with                                    ///
  ///  1) `_W_FEATURE` it is a writer only feature.                             ///
  ///  2) `_RW_FEATURE` it is a reader-writer feature.                          ///
  /////////////////////////////////////////////////////////////////////////////////
  public static final TableFeature APPEND_ONLY_W_FEATURE = new AppendOnlyFeature();

  private static class AppendOnlyFeature extends TableFeature.LegacyWriterFeature {
    AppendOnlyFeature() {
      super(/* featureName = */ "appendOnly", /* minWriterVersion = */ 2);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.APPEND_ONLY_ENABLED.fromMetadata(metadata);
    }

    @Override
    public boolean hasKernelWriteSupport(Metadata metadata) {
      return true;
    }
  }

  // TODO: [delta-io/delta#4763] Support `catalogManaged` when the RFC is formally accepted into the
  //       protocol.

  public static final TableFeature CATALOG_MANAGED_R_W_FEATURE_PREVIEW =
      new CatalogManagedFeatureBase("catalogOwned-preview");

  private static class CatalogManagedFeatureBase extends TableFeature.ReaderWriterFeature {
    CatalogManagedFeatureBase(String featureName) {
      super(featureName, /* minReaderVersion = */ 3, /* minWriterVersion = */ 7);
    }

    @Override
    public boolean hasKernelWriteSupport(Metadata metadata) {
      return false;
    }

    @Override
    public Set<TableFeature> requiredFeatures() {
      return Collections.singleton(IN_COMMIT_TIMESTAMP_W_FEATURE);
    }
  }

  public static final TableFeature INVARIANTS_W_FEATURE = new InvariantsFeature();

  private static class InvariantsFeature extends TableFeature.LegacyWriterFeature {
    InvariantsFeature() {
      super(/* featureName = */ "invariants", /* minWriterVersion = */ 2);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return hasInvariants(metadata.getSchema());
    }

    @Override
    public boolean hasKernelWriteSupport(Metadata metadata) {
      // If there is no invariant, then the table is supported
      return !hasInvariants(metadata.getSchema());
    }
  }

  public static final TableFeature CONSTRAINTS_W_FEATURE = new ConstraintsFeature();

  private static class ConstraintsFeature extends TableFeature.LegacyWriterFeature {
    ConstraintsFeature() {
      super("checkConstraints", /* minWriterVersion = */ 3);
    }

    @Override
    public boolean hasKernelWriteSupport(Metadata metadata) {
      // Kernel doesn't support table with constraints.
      return !hasCheckConstraints(metadata);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return hasCheckConstraints(metadata);
    }
  }

  public static final TableFeature CHANGE_DATA_FEED_W_FEATURE = new ChangeDataFeedFeature();

  private static class ChangeDataFeedFeature extends TableFeature.LegacyWriterFeature {
    ChangeDataFeedFeature() {
      super("changeDataFeed", /* minWriterVersion = */ 4);
    }

    @Override
    public boolean hasKernelWriteSupport(Metadata metadata) {
      // writable if change data feed is disabled
      return !TableConfig.CHANGE_DATA_FEED_ENABLED.fromMetadata(metadata);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.CHANGE_DATA_FEED_ENABLED.fromMetadata(metadata);
    }
  }

  public static final TableFeature COLUMN_MAPPING_RW_FEATURE = new ColumnMappingFeature();

  private static class ColumnMappingFeature extends TableFeature.LegacyReaderWriterFeature {
    ColumnMappingFeature() {
      super("columnMapping", /*minReaderVersion = */ 2, /* minWriterVersion = */ 5);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.COLUMN_MAPPING_MODE.fromMetadata(metadata) != NONE;
    }
  }

  public static final TableFeature GENERATED_COLUMNS_W_FEATURE = new GeneratedColumnsFeature();

  private static class GeneratedColumnsFeature extends TableFeature.LegacyWriterFeature {
    GeneratedColumnsFeature() {
      super("generatedColumns", /* minWriterVersion = */ 4);
    }

    @Override
    public boolean hasKernelWriteSupport(Metadata metadata) {
      // Kernel can write as long as there are no generated columns defined
      return !hasGeneratedColumns(metadata);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return hasGeneratedColumns(metadata);
    }
  }

  public static final TableFeature IDENTITY_COLUMNS_W_FEATURE = new IdentityColumnsFeature();

  private static class IdentityColumnsFeature extends TableFeature.LegacyWriterFeature {
    IdentityColumnsFeature() {
      super("identityColumns", /* minWriterVersion = */ 6);
    }

    @Override
    public boolean hasKernelWriteSupport(Metadata metadata) {
      return !hasIdentityColumns(metadata);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return hasIdentityColumns(metadata);
    }
  }

  /* ---- Start: variantType ---- */
  // Base class for variantType and variantType-preview features. Both features are same in terms
  // of behavior and given the feature is graduated, we will enable the `variantType` by default
  // if the metadata requirements are satisfied and the table doesn't already contain the
  // `variantType-preview` feature. Also to note, with this version of Kernel, one can't
  // auto upgrade to `variantType-preview` with metadata requirements. It can only be set
  // manually using `delta.feature.variantType-preview=supported` property.
  private static class VariantTypeTableFeatureBase extends TableFeature.ReaderWriterFeature {
    VariantTypeTableFeatureBase(String featureName) {
      super(featureName, /* minReaderVersion = */ 3, /* minWriterVersion = */ 7);
    }

    @Override
    public boolean hasKernelWriteSupport(Metadata metadata) {
      return false; // TODO: yet to be implemented in Kernel
    }
  }

  private static class VariantTypeTableFeature extends VariantTypeTableFeatureBase
      implements FeatureAutoEnabledByMetadata {
    VariantTypeTableFeature() {
      super("variantType");
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return hasTypeColumn(metadata.getSchema(), VARIANT)
          &&
          // Don't automatically enable the stable feature if the preview feature is
          // already supported, to avoid possibly breaking old clients that only
          // support the preview feature.
          !protocol.supportsFeature(VARIANT_RW_PREVIEW_FEATURE);
    }
  }

  public static final TableFeature VARIANT_RW_FEATURE = new VariantTypeTableFeature();
  public static final TableFeature VARIANT_RW_PREVIEW_FEATURE =
      new VariantTypeTableFeatureBase("variantType-preview");
  /* ---- End: variantType ---- */

  /* ---- Start: variantShredding-preview ---- */
  public static final TableFeature VARIANT_SHREDDING_PREVIEW_RW_FEATURE =
      new VariantShreddingPreviewFeature();

  private static class VariantShreddingPreviewFeature extends TableFeature.ReaderWriterFeature
      implements FeatureAutoEnabledByMetadata {
    VariantShreddingPreviewFeature() {
      super("variantShredding-preview", /* minReaderVersion = */ 3, /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.VARIANT_SHREDDING_ENABLED.fromMetadata(metadata);
    }

    @Override
    public boolean hasKernelWriteSupport(Metadata metadata) {
      return false; // TODO: yet to be implemented in Kernel
    }
  }
  /* ---- End: variantShredding-preview ---- */

  public static final TableFeature DOMAIN_METADATA_W_FEATURE = new DomainMetadataFeature();

  private static class DomainMetadataFeature extends TableFeature.WriterFeature {
    DomainMetadataFeature() {
      super("domainMetadata", /* minWriterVersion = */ 7);
    }
  }

  public static final TableFeature CLUSTERING_W_FEATURE = new ClusteringTableFeature();

  private static class ClusteringTableFeature extends TableFeature.WriterFeature {
    ClusteringTableFeature() {
      super("clustering", /* minWriterVersion = */ 7);
    }

    @Override
    public Set<TableFeature> requiredFeatures() {
      return Collections.singleton(DOMAIN_METADATA_W_FEATURE);
    }
  }

  public static final TableFeature ROW_TRACKING_W_FEATURE = new RowTrackingFeature();

  private static class RowTrackingFeature extends TableFeature.WriterFeature
      implements FeatureAutoEnabledByMetadata {
    RowTrackingFeature() {
      super("rowTracking", /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.ROW_TRACKING_ENABLED.fromMetadata(metadata);
    }

    @Override
    public Set<TableFeature> requiredFeatures() {
      return Collections.singleton(DOMAIN_METADATA_W_FEATURE);
    }
  }

  public static final TableFeature DELETION_VECTORS_RW_FEATURE = new DeletionVectorsTableFeature();

  /**
   * Kernel currently only support blind appends. So we don't need to do anything special for
   * writing into a table with deletion vectors enabled (i.e a table feature with DV enabled is both
   * readable and writable).
   */
  private static class DeletionVectorsTableFeature extends TableFeature.ReaderWriterFeature
      implements FeatureAutoEnabledByMetadata {
    DeletionVectorsTableFeature() {
      super("deletionVectors", /* minReaderVersion = */ 3, /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.DELETION_VECTORS_CREATION_ENABLED.fromMetadata(metadata);
    }
  }

  public static final TableFeature ICEBERG_COMPAT_V2_W_FEATURE = new IcebergCompatV2TableFeature();

  private static class IcebergCompatV2TableFeature extends TableFeature.WriterFeature
      implements FeatureAutoEnabledByMetadata {
    IcebergCompatV2TableFeature() {
      super("icebergCompatV2", /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.ICEBERG_COMPAT_V2_ENABLED.fromMetadata(metadata);
    }

    public @Override Set<TableFeature> requiredFeatures() {
      return Collections.singleton(COLUMN_MAPPING_RW_FEATURE);
    }
  }

  public static final TableFeature ICEBERG_COMPAT_V3_W_FEATURE = new IcebergCompatV3TableFeature();

  private static class IcebergCompatV3TableFeature extends TableFeature.WriterFeature
      implements FeatureAutoEnabledByMetadata {
    IcebergCompatV3TableFeature() {
      super("icebergCompatV3", /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.ICEBERG_COMPAT_V3_ENABLED.fromMetadata(metadata);
    }

    public @Override Set<TableFeature> requiredFeatures() {
      return Collections.unmodifiableSet(
          new HashSet<>(Arrays.asList(COLUMN_MAPPING_RW_FEATURE, ROW_TRACKING_W_FEATURE)));
    }
  }

  /* ---- Start: type widening ---- */
  // Base class for typeWidening and typeWidening-preview features. Both features are same in terms
  // of behavior and given the feature is graduated, we will enable the `typeWidening` by default
  // if the metadata requirements are satisfied and the table doesn't already contain the
  // `typeWidening-preview` feature. Also to note, with this version of Kernel, one can't
  // auto upgrade to `typeWidening-preview` with metadata requirements. It can only be set
  // manually using `delta.feature.typeWidening-preview=supported` property.
  private static class TypeWideningTableFeatureBase extends TableFeature.ReaderWriterFeature {
    TypeWideningTableFeatureBase(String featureName) {
      super(featureName, /* minReaderVersion = */ 3, /* minWriterVersion = */ 7);
    }
  }

  private static class TypeWideningTableFeature extends TypeWideningTableFeatureBase
      implements FeatureAutoEnabledByMetadata {
    TypeWideningTableFeature() {
      super("typeWidening");
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.TYPE_WIDENING_ENABLED.fromMetadata(metadata)
          &&
          // Don't automatically enable the stable feature if the preview feature is already
          // supported, to
          // avoid possibly breaking old clients that only support the preview feature.
          !protocol.supportsFeature(TYPE_WIDENING_RW_PREVIEW_FEATURE);
    }
  }

  public static final TableFeature TYPE_WIDENING_RW_FEATURE = new TypeWideningTableFeature();

  public static final TableFeature TYPE_WIDENING_RW_PREVIEW_FEATURE =
      new TypeWideningTableFeatureBase("typeWidening-preview");
  /* ---- End: type widening ---- */

  public static final TableFeature IN_COMMIT_TIMESTAMP_W_FEATURE =
      new InCommitTimestampTableFeature();

  private static class InCommitTimestampTableFeature extends TableFeature.WriterFeature
      implements FeatureAutoEnabledByMetadata {
    InCommitTimestampTableFeature() {
      super("inCommitTimestamp", /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.IN_COMMIT_TIMESTAMPS_ENABLED.fromMetadata(metadata);
    }
  }

  public static final TableFeature TIMESTAMP_NTZ_RW_FEATURE = new TimestampNtzTableFeature();

  private static class TimestampNtzTableFeature extends TableFeature.ReaderWriterFeature
      implements FeatureAutoEnabledByMetadata {
    TimestampNtzTableFeature() {
      super("timestampNtz", /* minReaderVersion = */ 3, /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return hasTypeColumn(metadata.getSchema(), TIMESTAMP_NTZ);
    }
  }

  public static final TableFeature CHECKPOINT_V2_RW_FEATURE = new CheckpointV2TableFeature();

  /**
   * In order to commit, there is no extra work required when v2 checkpoint is enabled. This affects
   * the checkpoint format only. When v2 is enabled, writing classic checkpoints is still allowed.
   */
  private static class CheckpointV2TableFeature extends TableFeature.ReaderWriterFeature
      implements FeatureAutoEnabledByMetadata {
    CheckpointV2TableFeature() {
      super("v2Checkpoint", /* minReaderVersion = */ 3, /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      // TODO: define an enum for checkpoint policy when we start supporting writing v2 checkpoints
      return "v2".equals(TableConfig.CHECKPOINT_POLICY.fromMetadata(metadata));
    }
  }

  public static final TableFeature VACUUM_PROTOCOL_CHECK_RW_FEATURE =
      new VacuumProtocolCheckTableFeature();

  private static class VacuumProtocolCheckTableFeature extends TableFeature.ReaderWriterFeature {
    VacuumProtocolCheckTableFeature() {
      super("vacuumProtocolCheck", /* minReaderVersion = */ 3, /* minWriterVersion = */ 7);
    }
  }

  public static final TableFeature ICEBERG_WRITER_COMPAT_V1 = new IcebergWriterCompatV1();

  private static class IcebergWriterCompatV1 extends TableFeature.WriterFeature
      implements FeatureAutoEnabledByMetadata {
    IcebergWriterCompatV1() {
      super("icebergWriterCompatV1", /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.ICEBERG_WRITER_COMPAT_V1_ENABLED.fromMetadata(metadata);
    }

    public @Override Set<TableFeature> requiredFeatures() {
      return Collections.singleton(ICEBERG_COMPAT_V2_W_FEATURE);
    }
  }

  public static final TableFeature ICEBERG_WRITER_COMPAT_V3 = new IcebergWriterCompatV3();

  private static class IcebergWriterCompatV3 extends TableFeature.WriterFeature
      implements FeatureAutoEnabledByMetadata {
    IcebergWriterCompatV3() {
      super("icebergWriterCompatV3", /* minWriterVersion = */ 7);
    }

    @Override
    public boolean metadataRequiresFeatureToBeEnabled(Protocol protocol, Metadata metadata) {
      return TableConfig.ICEBERG_WRITER_COMPAT_V3_ENABLED.fromMetadata(metadata);
    }

    public @Override Set<TableFeature> requiredFeatures() {
      return Collections.singleton(ICEBERG_COMPAT_V3_W_FEATURE);
    }
  }

  /////////////////////////////////////////////////////////////////////////////////
  /// END: Define the {@link TableFeature}s                                     ///
  /////////////////////////////////////////////////////////////////////////////////

  /////////////////////////////////////////////////////////////////////////////////
  /// Public static variables and methods                                       ///
  /////////////////////////////////////////////////////////////////////////////////
  /** Min reader version that supports reader features. */
  public static final int TABLE_FEATURES_MIN_READER_VERSION = 3;

  /** Min reader version that supports writer features. */
  public static final int TABLE_FEATURES_MIN_WRITER_VERSION = 7;

  public static final List<TableFeature> TABLE_FEATURES =
      Collections.unmodifiableList(
          Arrays.asList(
              APPEND_ONLY_W_FEATURE,
              CATALOG_MANAGED_R_W_FEATURE_PREVIEW,
              CHECKPOINT_V2_RW_FEATURE,
              CHANGE_DATA_FEED_W_FEATURE,
              CLUSTERING_W_FEATURE,
              COLUMN_MAPPING_RW_FEATURE,
              CONSTRAINTS_W_FEATURE,
              DELETION_VECTORS_RW_FEATURE,
              GENERATED_COLUMNS_W_FEATURE,
              DOMAIN_METADATA_W_FEATURE,
              ICEBERG_COMPAT_V2_W_FEATURE,
              ICEBERG_COMPAT_V3_W_FEATURE,
              IDENTITY_COLUMNS_W_FEATURE,
              IN_COMMIT_TIMESTAMP_W_FEATURE,
              INVARIANTS_W_FEATURE,
              ROW_TRACKING_W_FEATURE,
              TIMESTAMP_NTZ_RW_FEATURE,
              TYPE_WIDENING_RW_PREVIEW_FEATURE,
              TYPE_WIDENING_RW_FEATURE,
              VACUUM_PROTOCOL_CHECK_RW_FEATURE,
              VARIANT_RW_FEATURE,
              VARIANT_RW_PREVIEW_FEATURE,
              VARIANT_SHREDDING_PREVIEW_RW_FEATURE,
              ICEBERG_WRITER_COMPAT_V1,
              ICEBERG_WRITER_COMPAT_V3));

  public static final Map<String, TableFeature> TABLE_FEATURE_MAP =
      Collections.unmodifiableMap(
          new CaseInsensitiveMap<TableFeature>() {
            {
              for (TableFeature feature : TABLE_FEATURES) {
                put(feature.featureName(), feature);
              }
            }
          });

  /** Get the table feature by name. Case-insensitive lookup. If not found, throws error. */
  public static TableFeature getTableFeature(String featureName) {
    TableFeature tableFeature = TABLE_FEATURE_MAP.get(featureName);
    if (tableFeature == null) {
      throw DeltaErrors.unsupportedTableFeature(featureName);
    }
    return tableFeature;
  }

  /** Does reader version supports explicitly specifying reader feature set in protocol? */
  public static boolean supportsReaderFeatures(int minReaderVersion) {
    return minReaderVersion >= TABLE_FEATURES_MIN_READER_VERSION;
  }

  /** Does writer version supports explicitly specifying writer feature set in protocol? */
  public static boolean supportsWriterFeatures(int minWriterVersion) {
    return minWriterVersion >= TABLE_FEATURES_MIN_WRITER_VERSION;
  }

  /** Returns the minimum reader/writer versions required to support all provided features. */
  public static Tuple2<Integer, Integer> minimumRequiredVersions(Set<TableFeature> features) {
    int minReaderVersion =
        features.stream().mapToInt(TableFeature::minReaderVersion).max().orElse(0);

    int minWriterVersion =
        features.stream().mapToInt(TableFeature::minWriterVersion).max().orElse(0);

    return new Tuple2<>(Math.max(minReaderVersion, 1), Math.max(minWriterVersion, 1));
  }

  /**
   * Upgrade the current protocol to satisfy all auto-update capable features required by the given
   * metadata. If the current protocol already satisfies the metadata requirements, return empty.
   *
   * @param newMetadata the new metadata to be applied to the table.
   * @param manuallyEnabledFeatures features that were requested to be added to the protocol.
   * @param currentProtocol the current protocol of the table.
   * @return the upgraded protocol and the set of new features that were enabled in the upgrade.
   */
  public static Optional<Tuple2<Protocol, Set<TableFeature>>> autoUpgradeProtocolBasedOnMetadata(
      Metadata newMetadata,
      Collection<TableFeature> manuallyEnabledFeatures,
      Protocol currentProtocol) {

    Set<TableFeature> allNeededTableFeatures =
        extractAllNeededTableFeatures(newMetadata, currentProtocol);
    if (manuallyEnabledFeatures != null && !manuallyEnabledFeatures.isEmpty()) {
      // Note that any dependent features are handled below in the withFeatures call.
      allNeededTableFeatures =
          Stream.concat(allNeededTableFeatures.stream(), manuallyEnabledFeatures.stream())
              .collect(toSet());
    }

    Protocol required =
        new Protocol(TABLE_FEATURES_MIN_READER_VERSION, TABLE_FEATURES_MIN_WRITER_VERSION)
            .withFeatures(allNeededTableFeatures)
            .normalized();

    // See if all the required features are already supported in the current protocol.
    if (!required.canUpgradeTo(currentProtocol)) {
      // `required` has one or more features that are not supported in `currentProtocol`.
      Set<TableFeature> newFeatures =
          new HashSet<>(required.getImplicitlyAndExplicitlySupportedFeatures());
      newFeatures.removeAll(currentProtocol.getImplicitlyAndExplicitlySupportedFeatures());
      return Optional.of(new Tuple2<>(required.merge(currentProtocol), newFeatures));
    } else {
      return Optional.empty();
    }
  }

  /**
   * Extracts features overrides from Metadata properties and returns an updated metadata if any
   * overrides are present.
   *
   * <p>Overrides are specified using a key in th form {@linkplain
   * #SET_TABLE_FEATURE_SUPPORTED_PREFIX} + {featureName}. (e.g. {@code
   * delta.feature.icebergWriterCompatV1}). The value must be "supported" to add the feature.
   * Currently, removing values is not handled.
   *
   * @return A set of features that had overrides and Metadata object with the properties removed if
   *     any overrides were present.
   * @throws KernelException if the feature name for the override is invalid or the value is not
   *     equal to "supported".
   */
  public static Tuple2<Set<TableFeature>, Optional<Metadata>> extractFeaturePropertyOverrides(
      Metadata currentMetadata) {
    Set<TableFeature> features = new HashSet<>();
    Map<String, String> properties = currentMetadata.getConfiguration();
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      if (entry.getKey().startsWith(SET_TABLE_FEATURE_SUPPORTED_PREFIX)) {
        String featureName = entry.getKey().substring(SET_TABLE_FEATURE_SUPPORTED_PREFIX.length());

        TableFeature feature = getTableFeature(featureName);
        features.add(feature);
        if (!entry.getValue().equals("supported")) {
          throw DeltaErrors.invalidConfigurationValueException(
              entry.getKey(),
              entry.getValue(),
              "TableFeature override options may only have \"supported\" as there value");
        }
      }
    }

    if (features.isEmpty()) {
      return new Tuple2<>(features, Optional.empty());
    }

    Map<String, String> cleanedProperties =
        properties.entrySet().stream()
            .filter(e -> !e.getKey().startsWith(SET_TABLE_FEATURE_SUPPORTED_PREFIX))
            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    return new Tuple2<>(
        features, Optional.of(currentMetadata.withReplacedConfiguration(cleanedProperties)));
  }

  /** Utility method to check if the table with given protocol is readable by the Kernel. */
  public static void validateKernelCanReadTheTable(Protocol protocol, String tablePath) {
    if (protocol.getMinReaderVersion() > TABLE_FEATURES_MIN_READER_VERSION) {
      throw DeltaErrors.unsupportedReaderProtocol(tablePath, protocol.getMinReaderVersion());
    }

    Set<TableFeature> unsupportedFeatures =
        protocol.getImplicitlyAndExplicitlySupportedReaderWriterFeatures().stream()
            .filter(f -> !f.hasKernelReadSupport())
            .collect(toSet());

    if (!unsupportedFeatures.isEmpty()) {
      throw unsupportedReaderFeatures(
          tablePath, unsupportedFeatures.stream().map(TableFeature::featureName).collect(toSet()));
    }
  }

  /**
   * Utility method to check if the table with given protocol and metadata is writable by the
   * Kernel.
   */
  public static void validateKernelCanWriteToTable(
      Protocol protocol, Metadata metadata, String tablePath) {

    validateKernelCanReadTheTable(protocol, tablePath);

    if (protocol.getMinWriterVersion() > TABLE_FEATURES_MIN_WRITER_VERSION) {
      throw unsupportedWriterProtocol(tablePath, protocol.getMinWriterVersion());
    }

    Set<TableFeature> unsupportedFeatures =
        protocol.getImplicitlyAndExplicitlySupportedFeatures().stream()
            .filter(f -> !f.hasKernelWriteSupport(metadata))
            .collect(toSet());

    if (!unsupportedFeatures.isEmpty()) {
      throw unsupportedWriterFeatures(
          tablePath, unsupportedFeatures.stream().map(TableFeature::featureName).collect(toSet()));
    }
  }

  /////////////////////////////
  // Is feature X supported? //
  /////////////////////////////

  public static boolean isCatalogManagedSupported(Protocol protocol) {
    return protocol.supportsFeature(CATALOG_MANAGED_R_W_FEATURE_PREVIEW);
  }

  public static boolean isRowTrackingSupported(Protocol protocol) {
    return protocol.supportsFeature(ROW_TRACKING_W_FEATURE);
  }

  public static boolean isDomainMetadataSupported(Protocol protocol) {
    return protocol.supportsFeature(DOMAIN_METADATA_W_FEATURE);
  }

  public static boolean isClusteringTableFeatureSupported(Protocol protocol) {
    return protocol.supportsFeature(CLUSTERING_W_FEATURE);
  }

  ///////////////////////////
  // Does protocol have X? //
  ///////////////////////////

  public static boolean hasInvariants(StructType tableSchema) {
    return SchemaIterable.newSchemaIterableWithIgnoredRecursion(
            tableSchema,
            // invariants are not allowed in maps or arrays
            new Class<?>[] {MapType.class, ArrayType.class})
        .stream()
        .anyMatch(element -> element.getField().getMetadata().contains("delta.invariants"));
  }

  public static boolean hasCheckConstraints(Metadata metadata) {
    return metadata.getConfiguration().keySet().stream()
        .anyMatch(s -> s.startsWith("delta.constraints."));
  }

  public static boolean hasIdentityColumns(Metadata metadata) {
    return SchemaIterable.newSchemaIterableWithIgnoredRecursion(
            metadata.getSchema(),
            // invariants are not allowed in maps or arrays
            new Class<?>[] {MapType.class, ArrayType.class})
        .stream()
        .anyMatch(
            element -> {
              StructField field = element.getField();
              FieldMetadata fieldMetadata = field.getMetadata();

              // Check if the metadata contains the required keys
              boolean hasStart = fieldMetadata.contains("delta.identity.start");
              boolean hasStep = fieldMetadata.contains("delta.identity.step");
              boolean hasInsert = fieldMetadata.contains("delta.identity.allowExplicitInsert");

              // Verify that all or none of the three fields are present
              if (!((hasStart == hasStep) && (hasStart == hasInsert))) {
                throw new KernelException(
                    String.format(
                        "Inconsistent IDENTITY metadata for column %s detected: %s, %s, %s",
                        field.getName(), hasStart, hasStep, hasInsert));
              }

              // Return true only if all three fields are present
              return hasStart && hasStep && hasInsert;
            });
  }

  public static boolean hasGeneratedColumns(Metadata metadata) {
    return SchemaIterable.newSchemaIterableWithIgnoredRecursion(
            metadata.getSchema(),
            // don't expected generated columns in
            // nested columns
            new Class<?>[] {MapType.class, ArrayType.class})
        .stream()
        .anyMatch(
            element -> element.getField().getMetadata().contains("delta.generationExpression"));
  }

  /////////////////////////////////////////////////////////////////////////////////
  /// Private methods                                                           ///
  /////////////////////////////////////////////////////////////////////////////////
  /**
   * Extracts all table features (and their dependency features) that are enabled by the given
   * metadata and supported in existing protocol.
   */
  private static Set<TableFeature> extractAllNeededTableFeatures(
      Metadata newMetadata, Protocol currentProtocol) {
    Set<TableFeature> protocolSupportedFeatures =
        currentProtocol.getImplicitlyAndExplicitlySupportedFeatures();

    Set<TableFeature> metadataEnabledFeatures =
        TableFeatures.TABLE_FEATURES.stream()
            .filter(f -> f instanceof FeatureAutoEnabledByMetadata)
            .filter(
                f ->
                    ((FeatureAutoEnabledByMetadata) f)
                        .metadataRequiresFeatureToBeEnabled(currentProtocol, newMetadata))
            .collect(toSet());

    // Each feature may have dependencies that are not yet enabled in the protocol.
    Set<TableFeature> newFeatures = getDependencyFeatures(metadataEnabledFeatures);
    return Stream.concat(protocolSupportedFeatures.stream(), newFeatures.stream()).collect(toSet());
  }

  /**
   * Returns the smallest set of table features that contains `features` and that also contains all
   * dependencies of all features in the returned set.
   */
  private static Set<TableFeature> getDependencyFeatures(Set<TableFeature> features) {
    Set<TableFeature> requiredFeatures = new HashSet<>(features);
    features.forEach(feature -> requiredFeatures.addAll(feature.requiredFeatures()));

    if (features.equals(requiredFeatures)) {
      return features;
    } else {
      return getDependencyFeatures(requiredFeatures);
    }
  }

  /**
   * Check if the table schema has a column of type. Caution: works only for the primitive types.
   */
  private static boolean hasTypeColumn(StructType tableSchema, DataType type) {
    return new SchemaIterable(tableSchema)
        .stream().anyMatch(element -> element.getField().getDataType().equals(type));
  }
}
