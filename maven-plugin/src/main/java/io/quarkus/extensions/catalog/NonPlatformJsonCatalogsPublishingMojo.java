package io.quarkus.extensions.catalog;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import io.quarkus.registry.RepositoryIndexer;
import io.quarkus.registry.builder.RegistryBuilder;
import io.quarkus.registry.catalog.model.Repository;
import io.quarkus.registry.model.ImmutableExtension;
import io.quarkus.registry.model.ImmutablePlatform;
import io.quarkus.registry.model.ImmutableRegistry;
import io.quarkus.registry.model.Registry;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.installation.InstallRequest;
import org.eclipse.aether.installation.InstallResult;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.repository.RemoteRepository;

@Mojo(name = "non-platform-catalogs")
public class NonPlatformJsonCatalogsPublishingMojo extends AbstractMojo {

	private static final String JSON = "json";
	private static final String DASH_DEV = "-DEV";
	private static final String DASH_SNAPSHOT = "-SNAPSHOT";
	private static final String NON_PLATFORM_CATALOG_GROUP_ID = "io.quarkus.registry";
	private static final String NON_PLATFORM_CATALOG_ARTIFACT_ID = "quarkus-non-platform-extensions";
	
    @Parameter(defaultValue = "${project}")
    protected MavenProject project;

    @Component
    private RepositorySystem repoSystem;
    
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repos;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

	/**
	 * The repository path to index
	 */
	@Parameter(property = "repositoryDir", required = true)
	File repositoryDir;

	/**
	 * The output file/directory. A directory if --split is true, a file otherwise
	 */
	@Parameter(property = "output", defaultValue = "${project.build.directory}/json", required = true)
	File output;

	/**
	 * Split into versioned directories
	 */
	@Parameter(property = "split", defaultValue = "true", required = false)
	boolean split;

	@Component
	MavenSession mvnSession;
	
	BootstrapAppModelResolver resolver;
	
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		
		try {
			generateJson();
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to generate JSON catalogs", e);
		}
		
		publishCatalogs();
	}

	private void publishCatalogs() throws MojoExecutionException {
		boolean install = false;
		boolean deploy = false;
		if(mvnSession.getGoals().contains("install")) {
			install = true;
		} else if(mvnSession.getGoals().contains("deploy")) {
			install = true;
			deploy = true;
		}
		if(!install) {
			getLog().info("JSON catalogs are not going to be published");
			return;			
		}
		
		final Path jsonRootDir = output.toPath();
		if(!Files.isDirectory(jsonRootDir)) {
			getLog().warn(jsonRootDir + " does not exist or is not a directory");
			return;
		}
		
		int quarkusVersionsTotal = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(jsonRootDir)) {
			for(Path versionDir : stream) {
				if(!Files.isDirectory(versionDir)) {
					continue;
				}
				Path json = versionDir.resolve("catalog.json");
				if(!Files.exists(json)) {
					continue;
				}
				final String quarkusCoreVersion = versionDir.getFileName().toString();
				++quarkusVersionsTotal;

				final Artifact installJson = catalogAetherJson(quarkusCoreVersion, "1.0-SNAPSHOT", json);

				Path pom = versionDir.resolve(installJson.getArtifactId() + "-" + installJson.getVersion() + ".pom");
				Model model = new Model();
				model.setModelVersion("4.0.0");
				model.setGroupId(installJson.getGroupId());
				model.setArtifactId(installJson.getArtifactId());
				model.setVersion(installJson.getVersion());
				model.setPackaging("pom");
				ModelUtils.persistModel(pom, model);
				Artifact installPom = catalogAetherPom(quarkusCoreVersion, installJson.getVersion(), pom);
				
				Collection<Artifact> artifacts;
				try {
					final InstallResult result = repoSystem.install(repoSession, new InstallRequest().addArtifact(installPom).addArtifact(installJson));
					artifacts = result.getArtifacts();
				} catch (InstallationException e) {
					throw new MojoExecutionException("Failed to install " + installJson, e);
				}
		
				if (deploy) {
					try {
						RemoteRepository aetherRepo = RepositoryUtils
								.toRepo(project.getDistributionManagementArtifactRepository());
						if (aetherRepo.getAuthentication() == null || aetherRepo.getProxy() == null) {
							RemoteRepository.Builder builder = new RemoteRepository.Builder(aetherRepo);

							if (aetherRepo.getAuthentication() == null) {
								builder.setAuthentication(
										repoSession.getAuthenticationSelector().getAuthentication(aetherRepo));
							}

							if (aetherRepo.getProxy() == null) {
								builder.setProxy(repoSession.getProxySelector().getProxy(aetherRepo));
							}

							aetherRepo = builder.build();
						}

						repoSystem.deploy(repoSession,
								new DeployRequest().setArtifacts(artifacts).setRepository(aetherRepo));
					} catch (DeploymentException e) {
						throw new MojoExecutionException("Failed to deploy " + installJson, e);
					}
				}
			}
			
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to read directory " + jsonRootDir, e);
		}
		if(quarkusVersionsTotal == 0) {
			getLog().warn("Failed to locate Quarkus version-specific JSON catalogs under " + jsonRootDir);			
		}
	}

	public void generateJson() throws Exception {
		ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
				.setPropertyNamingStrategy(PropertyNamingStrategy.KEBAB_CASE);
		Repository repository = Repository.parse(repositoryDir.toPath(), mapper);
		RepositoryIndexer indexer = new RepositoryIndexer(new DefaultArtifactResolver());
		RegistryBuilder builder = new RegistryBuilder();
		indexer.index(repository, builder);
		// Make sure the parent directory exists
		final Path outputPath = output.toPath();
		Path parent = outputPath.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Registry registry = builder.build();
		if (split) {
			// Split into smaller files per version
			Files.createDirectories(outputPath);
			// Write the full output
			mapper.writeValue(outputPath.resolve("registry.json").toFile(), registry);
			mapper.writeValue(outputPath.resolve("versions.json").toFile(), registry.getCoreVersions());
			for (Map.Entry<ComparableVersion, Map<String, String>> entry : registry.getCoreVersions().entrySet()) {
				String version = entry.getKey().toString();
				Path versionedDir = Files.createDirectory(outputPath.resolve(version));
				ImmutableRegistry.Builder versionedRegistryBuilder = ImmutableRegistry.builder();
				List<ImmutableExtension> extensions = registry.getExtensions().stream().filter(
						e -> e.getReleases().stream().anyMatch(r -> r.getPlatforms().isEmpty()
								&& (version.equals(r.getRelease().getQuarkusCore())
										|| r.getRelease().getCompatibleQuarkusCore().contains(version))))
						.map(e -> ImmutableExtension.builder().from(e)
								.releases(e.getReleases().stream()
										.filter(r -> version.equals(r.getRelease().getQuarkusCore())
												|| r.getRelease().getCompatibleQuarkusCore().contains(version))
										.collect(toList()))
								.build())
						.collect(toList());
				Set<String> categoriesIds = extensions.stream()
						.map(e -> (List<String>) e.getMetadata().get("categories")).filter(Objects::nonNull)
						.flatMap(Collection::stream).collect(toSet());
				ImmutableRegistry newRegistry = versionedRegistryBuilder.putCoreVersions(entry)
						.addAllCategories(registry.getCategories().stream()
								.filter(c -> categoriesIds.contains(c.getId())).collect(toList()))
						.addAllPlatforms(registry.getPlatforms().stream()
								.filter(p -> p.getReleases().stream().anyMatch(r -> version.equals(r.getQuarkusCore())))
								.map(p -> ImmutablePlatform.builder().from(p)
										.releases(p.getReleases().stream()
												.filter(r -> version.equals(r.getQuarkusCore())
														|| r.getCompatibleQuarkusCore().contains(version))
												.collect(toList()))
										.build())
								.collect(toList()))
						.addAllExtensions(extensions).build();
				
				mapper.writeValue(versionedDir.resolve("registry.json").toFile(), newRegistry);
				
				QuarkusPlatformDescriptorImpl catalog = new QuarkusPlatformDescriptorImpl();
				catalog.quarkusVersion = version;
				for(ImmutableExtension e : extensions) {
					final io.quarkus.dependencies.Extension extension = new io.quarkus.dependencies.Extension();
					extension.setGroupId(e.getId().getGroupId());
					extension.setArtifactId(e.getId().getArtifactId());
					extension.setClassifier(null);
					extension.setType("jar");
					extension.setVersion(e.getReleases().iterator().next().getRelease().getVersion());
					extension.setName(e.getName());
					extension.setDescription(e.getDescription());
					extension.setMetadata(e.getMetadata());
					catalog.addExtension(extension);
				}
				
				mapper.writeValue(versionedDir.resolve("catalog.json").toFile(), catalog);
			}
		} else {
			// Just write the output
			mapper.writeValue(outputPath.toFile(), registry);
		}
	}

	private static Artifact catalogAetherJson(String quarkusCore, String version, Path json) {
		if(quarkusCore.endsWith(DASH_SNAPSHOT)) {
			quarkusCore = quarkusCore.substring(0, quarkusCore.length() - DASH_SNAPSHOT.length()) + DASH_DEV;
		}
		return new DefaultArtifact(NON_PLATFORM_CATALOG_GROUP_ID, NON_PLATFORM_CATALOG_ARTIFACT_ID + "-" + quarkusCore, null, JSON, version, null, json.toFile());
	}

	private static Artifact catalogAetherPom(String quarkusCore, String version, Path pom) {
		if(quarkusCore.endsWith(DASH_SNAPSHOT)) {
			quarkusCore = quarkusCore.substring(0, quarkusCore.length() - DASH_SNAPSHOT.length()) + DASH_DEV;
		}
		return new DefaultArtifact(NON_PLATFORM_CATALOG_GROUP_ID, NON_PLATFORM_CATALOG_ARTIFACT_ID + "-" + quarkusCore, null, "pom", version, null, pom.toFile());
	}

	private static AppArtifact catalogAppArtifact(String quarkusCore, String version) {
		if(quarkusCore.endsWith(DASH_SNAPSHOT)) {
			quarkusCore = quarkusCore.substring(0, quarkusCore.length() - DASH_SNAPSHOT.length()) + DASH_DEV;
		}
		return new AppArtifact(NON_PLATFORM_CATALOG_GROUP_ID, NON_PLATFORM_CATALOG_ARTIFACT_ID + "-" + quarkusCore, null, JSON, version);
	}

	private BootstrapAppModelResolver resolver() throws MojoExecutionException {
		if(resolver == null) {
			try {
				final BootstrapMavenContext mvnCtx = new BootstrapMavenContext(
						BootstrapMavenContext.config().setRepositorySystem(repoSystem)
								.setRepositorySystemSession(repoSession).setRemoteRepositories(repos));
				final MavenArtifactResolver mvnResolver = new MavenArtifactResolver(mvnCtx);
				resolver = new BootstrapAppModelResolver(mvnResolver);
			} catch (Exception e) {
				throw new MojoExecutionException("Failed to initialize Maven artifact resolver", e);
			}
		}
		return resolver;
	}
}
