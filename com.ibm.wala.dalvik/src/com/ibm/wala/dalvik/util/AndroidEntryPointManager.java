/*
 *  Copyright (c) 2013,
 *      Tobias Blaschke <code@tobiasblaschke.de>
 *  All rights reserved.

 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *  3. The names of the contributors may not be used to endorse or promote
 *     products derived from this software without specific prior written
 *     permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 *  AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 *  IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 *  ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 *  LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *  SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 *  INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 *  CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 *  ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *  POSSIBILITY OF SUCH DAMAGE.
 */
package com.ibm.wala.dalvik.util;

import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.dalvik.ipa.callgraph.impl.AndroidEntryPoint;
import com.ibm.wala.dalvik.ipa.callgraph.androidModel.parameters.IInstantiationBehavior;
import com.ibm.wala.dalvik.ipa.callgraph.androidModel.parameters.DefaultInstantiationBehavior;
import com.ibm.wala.dalvik.ipa.callgraph.androidModel.structure.AbstractAndroidModel;
import com.ibm.wala.dalvik.ipa.callgraph.androidModel.structure.LoopAndroidModel;

import com.ibm.wala.ipa.cha.IClassHierarchy;

import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;

import com.ibm.wala.util.ssa.SSAValueManager;

import com.ibm.wala.util.ssa.TypeSafeInstructionFactory;
import com.ibm.wala.ipa.summaries.VolatileMethodSummary;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.dalvik.ipa.callgraph.propagation.cfa.Intent;
import com.ibm.wala.classLoader.CallSiteReference;

import com.ibm.wala.util.strings.Atom;
import com.ibm.wala.util.strings.StringStuff;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import com.ibm.wala.util.collections.HashMapFactory;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.Class;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.NullProgressMonitor;

/**
 *  Model configuration and Global list of entrypoints.
 *
 *  AnalysisOptions.getEntrypoints may change during an analysis. This does not.
 *
 *  @author Tobias Blaschke <code@tobiasblaschke.de>
 */
public final /* singleton */ class AndroidEntryPointManager implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(AndroidEntryPointManager.class);

    public static final AndroidEntryPointManager MANAGER = new AndroidEntryPointManager();
    public static List<AndroidEntryPoint> ENTRIES = new ArrayList<AndroidEntryPoint>();
    /**
     * This is TRANSIENT!
     */
    private transient IInstantiationBehavior instantiation = null;

    //
    // EntryPoint stuff
    //
    /**
     *  Determines if any EntryPoint extends the specified component.
     */
    public boolean EPContainAny(AndroidComponent compo) {
        for (AndroidEntryPoint ep: ENTRIES) {
            if (ep.belongsTo(compo)) {
                return true;
            }
        }
        return false;
    }

    private AndroidEntryPointManager() {} 

    //
    //  General settings
    //

    /**
     *  Controls the instantiation of variables in the model.
     *
     *  On which occasions a new instance of a class shall be used? 
     *  This also changes the parameters to the later model.
     *
     *  @param  cha     Optional parameter given to the DefaultInstantiationBehavior if no other
     *      behavior has been set
     */
    public IInstantiationBehavior getInstantiationBehavior(IClassHierarchy cha) {
        if (this.instantiation == null) {
            this.instantiation = new DefaultInstantiationBehavior(cha);
        }
        return this.instantiation;
    }

    /**
     *  Set the value returned by {@link getInstantiationBehavior()}
     *
     *  @return the previous IInstantiationBehavior
     */
    public IInstantiationBehavior setInstantiationBehavior(IInstantiationBehavior instantiation) {
        final IInstantiationBehavior prev = this.instantiation;
        this.instantiation = instantiation;
        return prev;
    }

    private transient IProgressMonitor progressMonitor = null;
    /**
     *  Can be used to indicate the progress or to cancel operations.
     *
     *  @return a NullProgressMonitor or the one set before. 
     */
    public IProgressMonitor getProgressMonitor() {
        if (this.progressMonitor == null) {
            return new NullProgressMonitor();
        } else {
            return this.progressMonitor;
        }
    }

    /**
     *  Set the monitor returned by {@link #getProgressMonitor()}.
     */
    public IProgressMonitor setProgressMonitor(IProgressMonitor monitor) {
        IProgressMonitor prev = this.progressMonitor;
        this.progressMonitor = monitor;
        return prev;
    }

    private boolean doBootSequence = true;
    /**
     *  Whether to generate a global android environment.
     *
     *  It's possible to analyze android-applications without creating these structures and save 
     *  some memory. In this case some calls to the OS (like getting the Activity-manager or so)
     *  will not be able to be resolved.
     */
    public boolean getDoBootSequence() {
        return this.doBootSequence;
    }

    /**
     *  Whether to generate a global android environment.
     *
     *  See the {@link #getDoBootSequence()} documentation.
     *
     *  @return the previous setting of doBootSequence
     */
    public boolean setDoBootSequence(boolean doBootSequence) {
        boolean prev = this.doBootSequence;
        this.doBootSequence = doBootSequence;
        return prev;
    }

    private Class abstractAndroidModel = LoopAndroidModel.class;
    /**
     *  What special handling to insert into the model.
     *
     *  At given points in the model (called labels) special code is inserted into it (like loops).
     *  This setting controls what code is inserted there.
     *
     *  @see    com.ibm.wala.dalvik.ipa.callgraph.androidModel.structure.SequentialAndroidModel
     *  @see    com.ibm.wala.dalvik.ipa.callgraph.androidModel.structure.LoopAndroidModel
     *  @return An object that handles "events" that occur while generating the model.
     *  @throws IllegalStateException if initialization fails
     */
    public AbstractAndroidModel makeModelBehavior(VolatileMethodSummary body, TypeSafeInstructionFactory insts,
            SSAValueManager paramManager, Iterable<? extends Entrypoint> entryPoints) {
        if (abstractAndroidModel == null) {
            return new LoopAndroidModel(body, insts, paramManager, entryPoints);
        } else {
            try {
                final Constructor<AbstractAndroidModel> ctor = this.abstractAndroidModel.getDeclaredConstructor(
                    VolatileMethodSummary.class, TypeSafeInstructionFactory.class, SSAValueManager.class,
                    Iterable.class);
                if (ctor == null) {
                    throw new IllegalStateException("Canot find the constructor of " + this.abstractAndroidModel);
                }
                return (AbstractAndroidModel) ctor.newInstance(body, insts, paramManager, entryPoints);
            } catch (java.lang.InstantiationException e) {
                throw new IllegalStateException(e);
            } catch (java.lang.IllegalAccessException e) {
                throw new IllegalStateException(e);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new IllegalStateException(e);
            } catch (java.lang.NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     *  The behavior set using setModelBehavior(Class).
     *
     *  Use {@link makeModelBehavior(VolatileMethodSummary, JavaInstructionFactory, AndroidModelParameterManager, Iterable<? extends Entrypoint>} 
     *  to retrieve an instance of this class.
     *
     *  If no class was set it returns null, makeModelBehavior will generate a LoopAndroidModel by default.
     *
     *  @return null or the class set using setModelBehavior
     */
    public Class getModelBehavior() {
        return this.abstractAndroidModel;
    }

    /**
     *  Set the class instantiated by makeModelBehavior.
     *
     *  @throws IllgealArgumentException if the abstractAndroidModel does not subclass AbstractAndroidModel
     */
    public void setModelBehavior(Class abstractAndroidModel) {
        if (abstractAndroidModel == null) {
            throw new IllegalArgumentException("abstractAndroidModel may not be null. Use SequentialAndroidModel " +
                    "if no special handling shall be inserted.");
        }
        if (! AbstractAndroidModel.class.isAssignableFrom(abstractAndroidModel)) {
            throw new IllegalArgumentException("The given argument abstractAndroidModel does not subclass " +
                    "AbtractAndroidModel");
        }
        this.abstractAndroidModel = abstractAndroidModel;
    }

    //
    //  Propertys of the analyzed app
    //
    private transient String pack = null;
   
    /**
     *  Set the package of the analyzed application.
     *
     *  Setting the package of the application is completely optional. However if you do it it helps
     *  determining whether an Intent has an internal target.
     *
     *  @param  pack    The package of the analyzed application
     *  @throws IllegalArgumentException if the package has already been set and the value of the
     *      packages differ. Or if the given package is null.
     */
    public void setPackage(String pack) {
        if (pack == null) {
            throw new IllegalArgumentException("Setting the package to null is disallowed.");
        }
        if ((! pack.startsWith("L") || pack.contains("."))) {
            pack = StringStuff.deployment2CanonicalTypeString(pack);
        }
        if (this.pack == null) {
            logger.info("Setting the package to {}", pack);
            this.pack = pack;
        } else if (!(this.pack.equals(pack))) {
            throw new IllegalArgumentException("The already set package " + this.pack + " and " + pack +
                    " differ. You can only set pack once.");
        }
    }

    /**
     *  Return the package of the analyzed app.
     *
     *  This only returns a value other than null if the package has explicitly been set using 
     *  setPackage (which is for example called when reading in the Manifest).
     *
     *  If you didn't read the manifest you can still try and retrieve the package name using
     *  guessPackage().
     *
     *  @return The package or null if it was indeterminable.
     *  @see    guessPacakge()
     */
    public String getPackage() {
        if (this.pack == null) {
            logger.warn("Returning null as package");
            return null;
        } else {
            return this.pack;
        }
    }

    /**
     *  Get the package of the analyzed app.
     *
     *  If the package has been set using setPackage() return this value. Else try and determine 
     *  the package based on the first entrypoint.
     *
     *  @return The package or null if it was indeterminable.
     *  @see    getPackage()
     */
    public String guessPackage() {
        if (this.pack != null) {
            return this.pack;
        } else {
            if (ENTRIES.isEmpty()) {
                logger.error("guessPackage() called when no entrypoints had been set");
                return null;
            }
            final String first = ENTRIES.get(0).getMethod().getReference().getDeclaringClass().getName().getPackage().toString();
            // TODO: Iterate all?
            return first;
        }
    }
  
    //
    //  Intent stuff
    //

    /**
     *  Overrides Intents.
     *
     *  @see    com.ibm.wala.dalvik.ipa.callgraph.propagation.cfa.Intent
     *  @see    com.ibm.wala.dalvik.ipa.callgraph.propagation.cfa.IntentContextInterpreter
     */
    public final Map<Intent, Intent> overrideIntents = HashMapFactory.make();


    /**
     *  Set more information to an Intent.
     *
     *  You can call this method multiple times on the same Intent as long as you don't lower the associated
     *  information. So if you only want to change a specific value of it it is more safe to retrieve the Intent
     *  first and union it yourself before registering it.
     *
     *  @param  intent  An Intent with more or the same information as known to the system before.
     *  @throws IllegalArgumentException if you lower the information on an already registered Intent or the 
     *      information is incompatible.
     *  @see    registerIntentForce()
     */
    public void registerIntent(Intent intent) {
        if (overrideIntents.containsKey(intent)) {
            final Intent original = overrideIntents.get(intent);
            final Intent.IntentType oriType = original.getType();
            final Intent.IntentType newType = intent.getType();

            if ((newType == Intent.IntentType.UNKNOWN_TARGET) && (oriType != Intent.IntentType.UNKNOWN_TARGET)) {
                throw new IllegalArgumentException("You are lowering information on the Intent-Target of the " +
                        "Intent " + original + " from " + oriType + " to " + newType + ". Use registerIntentForce()" +
                        "If you are sure you want to do this!");
            } else if (oriType != newType) {
                throw new IllegalArgumentException("You are changing the Intents target to a contradicting one! " +
                        newType + "(new) is incompatible to " + oriType + "(before). On Intent " + intent +
                        ". Use registerIntentForce() if you are sure you want to do this!");
            }

            // TODO: Add actual target to the Intent and compare these?
            registerIntentForce(intent);
        } else {
            registerIntentForce(intent);
        }
    }

    /**
     *  Set intent possibly overwriting more specific information.
     *
     *  If you are sure that you want to override an existing registered Intent with information that is 
     *  possibly incompatible with the information originally set.
     */
    public void registerIntentForce(Intent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("The given Intent is null");
        }

        logger.info("Register Intent {}", intent);
        // Looks a bit weired but works as Intents are only matched based on their action and uri
        overrideIntents.put(intent, intent);
    }

    /**
     *  Override target of an Intent (or add an alias).
     *
     *  Use this for example to add an internal target to an implicit Intent, add an alias to an Intent, 
     *  resolve a System-name to an internal Intent, do weird stuff...
     *
     *  None of the Intents have to be registered before. However if the source is registered you may not 
     *  lower information on it.
     *
     *  Currently only one target to an Intent is supported! If you want to emulate multiple Targets you
     *  may have to add a synthetic class and register it as an Intent. If the target is not set to Internal
     *  multiple targets may implicitly emulated. See the Documentation for these targets for detail.
     *
     *  @param  from    the Intent to override
     *  @param  to      the new Intent to resolve once 'from' is seen
     *  @see    setOverrideForce()
     *  @throws IllegalArgumentException if you override an Intent with itself
     */
    public void setOverride(Intent from, Intent to) {
        if (from == null) {
            throw new IllegalArgumentException("The Intent given as 'from' is null");
        }
        if (to == null) {
            throw new IllegalArgumentException("The Intent given as 'to' is null");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("You cannot override an Intent with itself! If you want to " +
                    "alter Information on an Intent use registerIntent (you may register it multiple times).");
        }

        if (overrideIntents.containsKey(from)) {
            final Intent ori = overrideIntents.get(from);
            final Intent source;
            if (ori == from) {
                // The Intent has been registered before. Set the registered variant as source so Information
                // that may have been altered is not lost. Not that it would matter now...
                final Intent.IntentType oriType = ori.getType();
                final Intent.IntentType newType = from.getType();
                if ((newType == Intent.IntentType.UNKNOWN_TARGET) && (oriType != Intent.IntentType.UNKNOWN_TARGET)) {
                    // TODO: Test target resolvability
                    source = ori;
                } else {
                    source = from;
                }
            } else {
                source = from;
            }

            // Make sure the new target is not less specific than a known override
            final Intent original = overrideIntents.get(to);
            final Intent.IntentType oriType = original.getType();
            final Intent.IntentType newType = to.getType();

            if ((newType == Intent.IntentType.UNKNOWN_TARGET) && (oriType != Intent.IntentType.UNKNOWN_TARGET)) {
                throw new IllegalArgumentException("You are lowering information on the Intent-Target of the " +
                        "Intent " + original + " from " + oriType + " to " + newType + ". Use setOverrideForce()" +
                        "If you are sure you want to do this!");
            } else if (oriType != newType) {
                throw new IllegalArgumentException("You are changing the Intents target to a contradicting one! " +
                        newType + "(new) is incompatible to " + oriType + "(before). On Intent " + to +
                        ". Use setOverrideForce() if you are sure you want to do this!");
            }

            // TODO: Check resolvable Target is not overridden with unresolvable one

            setOverrideForce(source, to);
        } else {
            setOverrideForce(from, to);
        }
    }

    /**
     *  Just throw in the override.
     */
    public void setOverrideForce(Intent from, Intent to) {
        if (from == null) {
            throw new IllegalArgumentException("The Intent given as 'from' is null");
        }
        if (to == null) {
            throw new IllegalArgumentException("The Intent given as 'to' is null");
        }

        logger.info("Override Intent {} to {}", from, to);
        overrideIntents.put(from, to);
    }

    /**
     *  Get Intent with applied overrides.
     *
     *  If there are no overrides or the Intent is not registered return it as is.
     */
    public Intent getIntent(Intent intent) {
        if (overrideIntents.containsKey(intent)) {
            Intent ret = overrideIntents.get(intent);
            while (!(ret.equals(intent))) {
                // Follow the chain of overrides
                if (!overrideIntents.containsKey(intent)) {
                    logger.info("Resolved {} to {}", intent, ret);
                    return ret;
                } else {
                    logger.debug("Resolving {} hop over {}", intent, ret);
                    ret = overrideIntents.get(ret);
                }
            }
            ret = overrideIntents.get(ret); // Once again to get Info set in register
            logger.info("Resolved {} to {}", intent, ret);
            return ret;
        } else {
            logger.info("No information on {} hash: {}", intent, intent.hashCode());
            for (Intent known : overrideIntents.keySet()) {
                logger.debug("Known Intents: {} hash: {}", known, known.hashCode());
            }
            return intent;
        }
    }

    /**
     *  Searches Intent specifications for the occurrence of clazz.
     */
    public boolean existsIntentFor(TypeName clazz) {
        for (Intent i : overrideIntents.keySet()) {
            if (i.action.toString().equals(clazz.toString())) { // XXX toString-Matches are shitty
                return true;
            }
        }

        for (Intent i : overrideIntents.values()) {
            if (i.action.toString().equals(clazz.toString())) {
                return true;
            }
        }

        return false;
    }

    private transient Map<CallSiteReference, Intent> seenIntentCalls = HashMapFactory.make();
    /**
     *  DO NOT CALL! - This is for IntentContextSelector.
     *
     *  Add information that an Intent was called to the later summary. This is for information-purpose
     *  only and does not change any behavior.
     *
     *  Intents are added as seen - without any resolved overrides.
     */
    public void addCallSeen(CallSiteReference from, Intent intent) {
        seenIntentCalls.put(from, intent);
    }

    /**
     *  Return all Sites, that start Components based on Intents.
     */
    public  Map<CallSiteReference, Intent> getSeen() {
        return seenIntentCalls; // No need to make read-only
    }

    /**
     *  Last 8 digits encode the date.
     */
    private final static long serialVersionUID = 8740020131212L;
}
