package io.quarkus.extensions.catalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.model.Dependency;

import io.quarkus.dependencies.Category;
import io.quarkus.dependencies.Extension;
import io.quarkus.platform.descriptor.QuarkusPlatformDescriptor;
import io.quarkus.platform.descriptor.ResourceInputStreamConsumer;
import io.quarkus.platform.descriptor.ResourcePathConsumer;

public class QuarkusPlatformDescriptorImpl implements QuarkusPlatformDescriptor {

	String quarkusVersion;
	private List<Extension> extensions = new ArrayList<>();
	
	@Override
	public String getBomGroupId() {
		return "io.quarkus";
	}

	@Override
	public String getBomArtifactId() {
		return "quarkus-bom";
	}

	@Override
	public String getBomVersion() {
		return quarkusVersion;
	}

	@Override
	public String getQuarkusVersion() {
		return quarkusVersion;
	}

	@Override
	public List<Dependency> getManagedDependencies() {
		return Collections.emptyList();
	}

	void addExtension(Extension e) {
		extensions.add(e);
	}
	
	@Override
	public List<Extension> getExtensions() {
		return extensions;
	}

	@Override
	public List<Category> getCategories() {
		return Collections.emptyList();
	}

	@Override
	public String getTemplate(String name) {
		throw new UnsupportedOperationException();
	}

	@Override
	public <T> T loadResource(String name, ResourceInputStreamConsumer<T> consumer) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public <T> T loadResourceAsPath(String name, ResourcePathConsumer<T> consumer) throws IOException {
		throw new UnsupportedOperationException();
	}
}
