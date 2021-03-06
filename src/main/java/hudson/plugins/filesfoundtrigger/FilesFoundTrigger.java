/*
 * The MIT License
 * 
 * Copyright (c) 2011 Steven G. Brown
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.filesfoundtrigger;

import static hudson.Util.fixNull;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.common.collect.ImmutableList;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.reflection.PureJavaReflectionProvider;
import com.thoughtworks.xstream.mapper.Mapper;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.model.BuildableItem;
import hudson.model.Item;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.RobustReflectionConverter;

/**
 * Build trigger that schedules a build when certain files are found. These
 * files are declared using
 * <a href="http://ant.apache.org/manual/dirtasks.html">Ant-style file
 * patterns</a>.
 * 
 * @author Steven G. Brown
 */
public final class FilesFoundTrigger extends Trigger<BuildableItem> {

  private static final Logger LOGGER = Logger.getLogger(FilesFoundTrigger.class.getName());

  private static final AtomicLong logCounter = new AtomicLong();

  /**
   * The slave node on which to look for files, or {@code null} if the master
   * will be used.
   */
  private final String node;

  /**
   * The base directory to use when locating files.
   */
  private final String directory;

  /**
   * The pattern of files to locate under the base directory.
   */
  private final String files;

  /**
   * The pattern of files to ignore when searching under the base directory.
   */
  private final String ignoredFiles;

  /**
   * The build is triggered when the number of files found is greater than or
   * equal to this number.
   */
  private final String triggerNumber;

  /**
   * List of additional configured file patterns.
   * <p>
   * Declared as an ArrayList to provide a consistent XML format.
   */
  private final ArrayList<FilesFoundTriggerConfig> additionalConfigs;

  /**
   * Create a new {@link FilesFoundTrigger}.
   * 
   * @param spec
   *          crontab specification that defines how often to poll
   * @param configs
   *          the list of configured file patterns
   * @throws ANTLRException
   *           if unable to parse the crontab specification
   */
  @DataBoundConstructor
  public FilesFoundTrigger(String spec, List<FilesFoundTriggerConfig> configs)
      throws ANTLRException {
    super(spec);

    ArrayList<FilesFoundTriggerConfig> configsCopy = new ArrayList<FilesFoundTriggerConfig>(
        fixNull(configs));
    FilesFoundTriggerConfig firstConfig;
    if (configsCopy.isEmpty()) {
      firstConfig = new FilesFoundTriggerConfig(null, "", "", "", "1");
    } else {
      firstConfig = configsCopy.remove(0);
    }
    this.node = firstConfig.getNode();
    this.directory = firstConfig.getDirectory();
    this.files = firstConfig.getFiles();
    this.ignoredFiles = firstConfig.getIgnoredFiles();
    this.triggerNumber = firstConfig.getTriggerNumber();
    if (configsCopy.isEmpty()) {
      configsCopy = null;
    }
    this.additionalConfigs = configsCopy;
  }

  /**
   * Constructor intended to be called by XStream only. Sets the default field
   * values, which will then be overridden if these fields exist in the
   * configuration file.
   */
  @SuppressWarnings("unused")
  // called reflectively by XStream
  private FilesFoundTrigger() {
    this.node = null;
    this.directory = "";
    this.files = "";
    this.ignoredFiles = "";
    this.triggerNumber = "1";
    this.additionalConfigs = null;
  }

  /**
   * Get the list of configured file patterns.
   * 
   * @return a list of {@link FilesFoundTriggerConfig}
   */
  public List<FilesFoundTriggerConfig> getConfigs() {
    ImmutableList.Builder<FilesFoundTriggerConfig> builder = ImmutableList.builder();
    builder.add(new FilesFoundTriggerConfig(node, directory, files, ignoredFiles, triggerNumber));
    if (additionalConfigs != null) {
      builder.addAll(additionalConfigs);
    }
    return builder.build();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void run() {
    long counter = logCounter.incrementAndGet();
    for (FilesFoundTriggerConfig config : getConfigs()) {
      FilesFoundTriggerConfig expandedConfig = config.expand();
      LOGGER.log(Level.FINE, "{0} - Searching for {1}", new Object[] { counter, expandedConfig });
      try {
        FileSearch.Result result = FileSearch.perform(expandedConfig);
        int triggerNumber = Integer.parseInt(expandedConfig.getTriggerNumber());
        boolean triggerBuild = result.files.size() >= triggerNumber;
        LOGGER.log(Level.FINE, "{0} - Result: {1} ({2})", new Object[] { counter,
            result.formValidation, triggerBuild ? "build triggered" : "build not triggered" });
        if (triggerBuild) {
          job.scheduleBuild(0, new FilesFoundTriggerCause(expandedConfig));
          return;
        }
      } catch (NumberFormatException e) {
        LOGGER.log(Level.FINE, "{0} - Result: Invalid trigger number (build not triggered)",
            counter);
      } catch (InterruptedException e) {
        LOGGER.log(Level.FINE, "{0} - Result: Thread interrupted (build not triggered)", counter);
        Thread.currentThread().interrupt();
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, MessageFormat.format("{0} - Searching for {1}. Result: {2}",
            new Object[] { counter, expandedConfig, e.getMessage() }), e);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString() {
    return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).append("spec", spec)
        .append("configs", getConfigs()).toString();
  }

  /**
   * {@link Converter} implementation for XStream. This converter uses the
   * {@link PureJavaReflectionProvider}, which ensures that the default
   * constructor is called.
   */
  public static final class ConverterImpl extends RobustReflectionConverter {

    /**
     * Class constructor.
     * 
     * @param mapper
     *          the mapper
     */
    public ConverterImpl(Mapper mapper) {
      super(mapper, new PureJavaReflectionProvider());
    }
  }

  /**
   * Registers {@link FilesFoundTrigger} as a {@link Trigger} extension.
   */
  @Extension
  public static final class DescriptorImpl extends TriggerDescriptor {

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isApplicable(Item item) {
      return item instanceof BuildableItem;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
      return Messages.DisplayName();
    }
  }
}
