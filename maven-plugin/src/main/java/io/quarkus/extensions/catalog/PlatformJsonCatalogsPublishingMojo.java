package io.quarkus.extensions.catalog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import io.quarkus.registry.RepositoryIndexer;
import io.quarkus.registry.builder.RegistryBuilder;
import io.quarkus.registry.catalog.model.Repository;
import io.quarkus.registry.model.Registry;
import io.quarkus.registry.model.Release;

@Mojo(name = "platform-catalogs")
public class PlatformJsonCatalogsPublishingMojo extends AbstractMojo {

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

	@Parameter(property = "defaultPlatformGroupId")
	String defaultPlatformGroupId = "io.quarkus";
	
	@Parameter(property = "defaultPlatformArtifactId")
	String defaultPlatformArtifactId = "quarkus-bom";
	
	@Parameter(property = "defaultPlatformVersion")
	String defaultPlatformVersion;

	@Parameter(property = "jsonGroupId")
	String jsonGroupId = "io.quarkus.registry";

	@Parameter(property = "jsonArtifactId")
	String jsonArtifactId = "quarkus-platforms";

	@Component
	MavenSession mvnSession;

	BootstrapAppModelResolver resolver;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		
		// here we are just selecting the latest versions as the default ones for now

		final Registry registry = indexRepository();		
		latestPlatforms(registry);
		publishDefaultPlatforms();

		publishPlatformsPerQuarkusCore(registry);
	}

	private void publishPlatformsPerQuarkusCore(Registry registry) throws MojoExecutionException {
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

		final ArtifactVersion defPlatformVersion = defaultPlatformVersion == null ? null : new DefaultArtifactVersion(defaultPlatformVersion);
		final Map<String, Map<AppArtifactKey, ArtifactVersion>> platformBuilders = new HashMap<>();
		registry.getPlatforms().forEach(p -> {
			p.getReleases().forEach(r -> {
				final Map<AppArtifactKey, ArtifactVersion> platformVersions = platformBuilders.computeIfAbsent(r.getQuarkusCore(), v -> new HashMap<>());
				final AppArtifactKey key = new AppArtifactKey(p.getId().getGroupId(), p.getId().getArtifactId());
				final ArtifactVersion nextVersion = new DefaultArtifactVersion(r.getVersion());
				final ArtifactVersion lastVersion = platformVersions.get(key);
				if (lastVersion == null || nextVersion.compareTo(lastVersion) > 0
						&& !(p.getId().getArtifactId().equals(defaultPlatformArtifactId)
								&& p.getId().getGroupId().equals(defaultPlatformGroupId)
								&& lastVersion.equals(defPlatformVersion))) {
					platformVersions.put(key, nextVersion);
				}
			});
		});
		
		final Path jsonRootDir = output.toPath();
		if(!Files.isDirectory(jsonRootDir)) {
			getLog().warn(jsonRootDir + " does not exist or is not a directory");
			return;
		}
		
		for(Map.Entry<String, Map<AppArtifactKey, ArtifactVersion>> corePlatforms : platformBuilders.entrySet()) {
			String quarkusCoreVersion = corePlatforms.getKey();
			if(quarkusCoreVersion.endsWith("-SNAPSHOT")) {
				quarkusCoreVersion = quarkusCoreVersion.substring(0, quarkusCoreVersion.length() - "SNAPSHOT".length()) + "DEV";
			}

			final Path versionDir = jsonRootDir.resolve(quarkusCoreVersion);
			if(!Files.exists(versionDir)) {
				try {
					Files.createDirectories(versionDir);
				} catch (IOException e) {
					throw new MojoExecutionException("Failed to create directory " + versionDir);
				}
			}

			final DefaultPlatforms.Builder platformBuilder = DefaultPlatforms.builder();
			platformBuilder.defaultPlatform("io.quarkus", "quarkus-bom");
			for(Map.Entry<AppArtifactKey, ArtifactVersion> platformVersion : corePlatforms.getValue().entrySet()) {
				platformBuilder.addPlatform(platformVersion.getKey().getGroupId(), platformVersion.getKey().getArtifactId(), platformVersion.getValue().toString());
				if(platformVersion.getKey().getArtifactId().equals(defaultPlatformArtifactId) && platformVersion.getKey().getGroupId().equals(defaultPlatformGroupId)) {
					platformBuilder.defaultPlatform(defaultPlatformGroupId, defaultPlatformArtifactId);
				}
			}
			
			final AppArtifact catalogArtifact = new AppArtifact(jsonGroupId, jsonArtifactId + "-" + quarkusCoreVersion, null, "json", "1.0-SNAPSHOT");
			final Path json = versionDir.resolve(jsonArtifactId + "-1.0-SNAPSHOT.json");
			persistJson(platformBuilder.build(), json);

			final Artifact installJson = new DefaultArtifact(catalogArtifact.getGroupId(), catalogArtifact.getArtifactId(), catalogArtifact.getClassifier(), catalogArtifact.getType(), "1.0-SNAPSHOT", null, json.toFile());

			Path pom = versionDir.resolve(catalogArtifact.getArtifactId() + "-" + installJson.getVersion() + ".pom");
			Model model = new Model();
			model.setModelVersion("4.0.0");
			model.setGroupId(installJson.getGroupId());
			model.setArtifactId(installJson.getArtifactId());
			model.setVersion(installJson.getVersion());
			model.setPackaging("pom");
			try {
				ModelUtils.persistModel(pom, model);
			} catch (IOException e1) {
				throw new MojoExecutionException("Failed to persist POM", e1);
			}
			Artifact installPom = new DefaultArtifact(installJson.getGroupId(), installJson.getArtifactId(), null, "pom", installJson.getVersion(), null, pom.toFile());
			
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
	}

	private void latestPlatforms(Registry registry) throws MojoExecutionException {
		final DefaultPlatforms.Builder platformsBuilder = DefaultPlatforms.builder().defaultPlatform(defaultPlatformGroupId, defaultPlatformArtifactId);		
		registry.getPlatforms().forEach(p -> {
			final Iterator<Release> releases = p.getReleases().iterator();
			if(!releases.hasNext()) {
				return;
			}
			if(defaultPlatformVersion != null
					&& p.getId().getGroupId().equals(defaultPlatformGroupId)
					&& p.getId().getArtifactId().equals(defaultPlatformArtifactId)) {
				boolean defaultVersionPresent = false;
				for(Release r : p.getReleases()) {
					if(r.getVersion().equals(defaultPlatformVersion)) {
						defaultVersionPresent = true;
						break;
					}
				}
				if(!defaultVersionPresent) {
					throw new IllegalStateException("Failed to locate the specified default version " + defaultPlatformVersion + " for platform " + defaultPlatformGroupId + ":" + defaultPlatformArtifactId);
				}
				platformsBuilder.addPlatform(defaultPlatformGroupId, defaultPlatformArtifactId, defaultPlatformVersion);
				return;
			}
			
			
			String latestStr = releases.next().getVersion();
			DefaultArtifactVersion latest = new DefaultArtifactVersion(latestStr);
			while(releases.hasNext()) {
				final String nextStr = releases.next().getVersion();
				final DefaultArtifactVersion next = new DefaultArtifactVersion(nextStr);
				if(next.compareTo(latest) > 0) {
					latestStr = nextStr;
					latest = next;
				}
			}
			platformsBuilder.addPlatform(p.getId().getGroupId(), p.getId().getArtifactId(), latestStr);
		});
		
		// Make sure the parent directory exists
		final Path outputPath = output.toPath();
		Path parent = outputPath.toAbsolutePath().getParent();
		if (parent != null) {
			try {
				Files.createDirectories(parent);
			} catch (IOException e) {
				throw new MojoExecutionException("Failed to create directory " + parent, e);
			}
		}
		persistJson(platformsBuilder.build(), outputPath.resolve("default-platforms.json"));
	}

	private void publishDefaultPlatforms() throws MojoExecutionException {
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

		Path json = jsonRootDir.resolve("default-platforms.json");
		if(!Files.exists(json)) {
			throw new MojoExecutionException("Failed to locate " + json);
		}

		final Artifact installJson = new DefaultArtifact(jsonGroupId, jsonArtifactId, null, "json", "1.0-SNAPSHOT", null, json.toFile());

		Path pom = jsonRootDir.resolve(jsonArtifactId + "-" + installJson.getVersion() + ".pom");
		Model model = new Model();
		model.setModelVersion("4.0.0");
		model.setGroupId(installJson.getGroupId());
		model.setArtifactId(installJson.getArtifactId());
		model.setVersion(installJson.getVersion());
		model.setPackaging("pom");
		try {
			ModelUtils.persistModel(pom, model);
		} catch (IOException e1) {
			throw new MojoExecutionException("Failed to persist POM " + pom, e1);
		}
		Artifact installPom = new DefaultArtifact(jsonGroupId, jsonArtifactId, null, "json", "1.0-SNAPSHOT", null, pom.toFile());
		
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
	
	private void persistJson(DefaultPlatforms platforms, Path targetFile) throws MojoExecutionException {
		final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
				.setPropertyNamingStrategy(PropertyNamingStrategy.KEBAB_CASE);		
		try {
			mapper.writeValue(targetFile.toFile(), platforms);
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to serialize the default platforms list to JSON", e);
		}
	}

	private Registry indexRepository() throws MojoExecutionException {
		ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
				.setPropertyNamingStrategy(PropertyNamingStrategy.KEBAB_CASE);
		Repository repository = Repository.parse(repositoryDir.toPath(), mapper);
		RepositoryIndexer indexer = new RepositoryIndexer(new DefaultArtifactResolver());
		RegistryBuilder builder = new RegistryBuilder();
		try {
			indexer.index(repository, builder);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to index the repository", e);
		}
		return builder.build();
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

	public static class DefaultPlatforms {
		
		public class Builder {
			private Builder() {
			}
		
			public Builder defaultPlatform(String groupId, String artifactId) {
				defaultPlatform = new DefaultPlatform(groupId, artifactId);
				return this;
			}
		
			public Builder addPlatform(String groupId, String artifactId, String version) {
				platforms.add(new Platform(groupId, artifactId, version));
				return this;
			}

			public DefaultPlatforms build() {
				boolean defaultPlatformListed = false;
				for(Platform p : platforms) {
					if(p.groupId.equals(defaultPlatform.groupId) && p.artifactId.equals(defaultPlatform.artifactId)) {
						defaultPlatformListed = true;
						break;
					}
				}
				if(!defaultPlatformListed) {
					throw new IllegalStateException("The default platform " + defaultPlatform.groupId + ":" + defaultPlatform.artifactId + " is not present in the list of platforms");
				}
				return DefaultPlatforms.this;
			}
		}
		
		public static Builder builder() {
			return new DefaultPlatforms().new Builder();
		}
		
		private DefaultPlatform defaultPlatform;
		private List<Platform> platforms = new ArrayList<>();

		public DefaultPlatform getDefaultPlatform() {
			return defaultPlatform;
		}

		public List<Platform> getPlatforms() {
			return platforms;
		}

		public void setPlatforms(List<Platform> platforms) {
			this.platforms = platforms;
		}
	}
	
	public static class DefaultPlatform {
		public String groupId;
		public String artifactId;
		
		public DefaultPlatform() {
		}

		public DefaultPlatform(String groupId, String artifactId) {
			this.groupId = groupId;
			this.artifactId = artifactId;
		}
	}

	public static class Platform {
		public String groupId;
		public String artifactId;
		public String version;
		
		public Platform() {
		}

		public Platform(String groupId, String artifactId, String version) {
			this.groupId = groupId;
			this.artifactId = artifactId;
			this.version = version;
		}
	}
}
