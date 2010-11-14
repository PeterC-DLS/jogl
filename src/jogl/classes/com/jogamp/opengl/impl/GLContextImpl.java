/*
 * Copyright (c) 2003 Sun Microsystems, Inc. All Rights Reserved.
 * Copyright (c) 2010 JogAmp Community. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * - Redistribution of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
 * INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
 * MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
 * ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
 * DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
 * ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
 * SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 * 
 * You acknowledge that this software is not designed or intended for use
 * in the design, construction, operation or maintenance of any nuclear
 * facility.
 * 
 * Sun gratefully acknowledges that this software was originally authored
 * and developed by Kenneth Bradley Russell and Christopher John Kline.
 */

package com.jogamp.opengl.impl;

import com.jogamp.common.os.DynamicLookupHelper;
import com.jogamp.common.util.ReflectionUtil;
import com.jogamp.gluegen.runtime.FunctionAddressResolver;
import com.jogamp.gluegen.runtime.ProcAddressTable;
import com.jogamp.gluegen.runtime.opengl.GLExtensionNames;
import com.jogamp.gluegen.runtime.opengl.GLProcAddressResolver;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import javax.media.nativewindow.AbstractGraphicsConfiguration;
import javax.media.nativewindow.AbstractGraphicsDevice;
import javax.media.nativewindow.NativeSurface;
import javax.media.opengl.GL;
import javax.media.opengl.GLCapabilities;
import javax.media.opengl.GLContext;
import javax.media.opengl.GLDrawable;
import javax.media.opengl.GLException;
import javax.media.opengl.GLProfile;

public abstract class GLContextImpl extends GLContext {
  protected static final boolean DEBUG = Debug.debug("GLContext");
  protected static final boolean VERBOSE = Debug.verbose();

  protected GLContextLock lock = new GLContextLock();

  /**
   * Context full qualified name: display_type + display_connection + major + minor + ctp.
   * This is the key for all cached ProcAddressTables, etc, to support multi display/device setups.
   */
  protected String contextFQN;

  // Cache of the functions that are available to be called at the current
  // moment in time
  protected ExtensionAvailabilityCache extensionAvailability;
  // Table that holds the addresses of the native C-language entry points for
  // OpenGL functions.
  private ProcAddressTable glProcAddressTable;

  // Tracks creation and initialization of buffer objects to avoid
  // repeated glGet calls upon glMapBuffer operations
  private GLBufferSizeTracker bufferSizeTracker; // Singleton - Set by GLContextShareSet
  private GLBufferStateTracker bufferStateTracker = new GLBufferStateTracker();
  private GLStateTracker glStateTracker = new GLStateTracker();

  protected GLDrawableImpl drawable;
  protected GLDrawableImpl drawableRead;

  protected GL gl;

  protected static final Object mappedContextTypeObjectLock;
  protected static final HashMap mappedExtensionAvailabilityCache;
  protected static final HashMap mappedGLProcAddress;
  protected static final HashMap mappedGLXProcAddress;

  static {
      mappedContextTypeObjectLock = new Object();
      mappedExtensionAvailabilityCache = new HashMap();
      mappedGLProcAddress = new HashMap();
      mappedGLXProcAddress = new HashMap();
  }

  public GLContextImpl(GLDrawableImpl drawable, GLDrawableImpl drawableRead, GLContext shareWith) {
    super();

    if (shareWith != null) {
      GLContextShareSet.registerSharing(this, shareWith);
    }
    GLContextShareSet.registerForBufferObjectSharing(shareWith, this);

    this.drawable = drawable;
    setGLDrawableRead(drawableRead);
  }

  public GLContextImpl(GLDrawableImpl drawable, GLContext shareWith) {
    this(drawable, null, shareWith);
  }

  protected void resetStates() {
      // Because we don't know how many other contexts we might be
      // sharing with (and it seems too complicated to implement the
      // GLObjectTracker's ref/unref scheme for the buffer-related
      // optimizations), simply clear the cache of known buffers' sizes
      // when we destroy contexts
      if (bufferSizeTracker != null) {
          bufferSizeTracker.clearCachedBufferSizes();
      }

      if (bufferStateTracker != null) {
          bufferStateTracker.clearBufferObjectState();
      }

      if (glStateTracker != null) {
          glStateTracker.clearStates(false);
      }

      extensionAvailability = null;
      glProcAddressTable = null;
      gl = null;
      contextFQN = null;

      super.resetStates();
  }

  public void setGLDrawableRead(GLDrawable read) {
    boolean lockHeld = lock.isHeld();
    if(lockHeld) {
        release();
    }
    drawableRead = ( null != read ) ? (GLDrawableImpl) read : drawable;
    if(lockHeld) {
        makeCurrent();
    }
  }

  public GLDrawable getGLDrawable() {
    return drawable;
  }

  public GLDrawable getGLDrawableRead() {
    return drawableRead;
  }

  public GLDrawableImpl getDrawableImpl() {
    return (GLDrawableImpl) getGLDrawable();
  }

  public final GL getGL() {
    return gl;
  }

  public GL setGL(GL gl) {
    if(DEBUG) {
        String sgl1 = (null!=this.gl)?this.gl.getClass().toString()+", "+this.gl.toString():"<null>";
        String sgl2 = (null!=gl)?gl.getClass().toString()+", "+gl.toString():"<null>";
        Exception e = new Exception("Info: setGL (OpenGL "+getGLVersion()+"): "+Thread.currentThread()+", "+sgl1+" -> "+sgl2);
        e.printStackTrace();
    }
    this.gl = gl;
    return gl;
  }

  // This is only needed for Mac OS X on-screen contexts
  protected void update() throws GLException { }

  public boolean isSynchronized() {
    return !lock.getFailFastMode();
  }

  public void setSynchronized(boolean isSynchronized) {
    lock.setFailFastMode(!isSynchronized);
  }

  public abstract Object getPlatformGLExtensions();

  // Note: the surface is locked within [makeCurrent .. swap .. release]
  public void release() throws GLException {
    if (!lock.isHeld()) {
      throw new GLException("Context not current on current thread");
    }
    setCurrent(null);
    try {
        releaseImpl();
    } finally {
      if (drawable.isSurfaceLocked()) {
          drawable.unlockSurface();
      }
      lock.unlock();
    }
  }
  protected abstract void releaseImpl() throws GLException;

  public final void destroy() {
    if (lock.isHeld()) {
        // release current context 
        release();
    }

    // Must hold the lock around the destroy operation to make sure we
    // don't destroy the context out from under another thread rendering to it
    lock.lock();
    try {
      /* FIXME: refactor dependence on Java 2D / JOGL bridge
      if (tracker != null) {
        // Don't need to do anything for contexts that haven't been
        // created yet
        if (isCreated()) {
          // If we are tracking creation and destruction of server-side
          // OpenGL objects, we must decrement the reference count of the
          // GLObjectTracker upon context destruction.
          //
          // Note that we can only eagerly delete these server-side
          // objects if there is another context currrent right now
          // which shares textures and display lists with this one.
          tracker.unref(deletedObjectTracker);
        }
      }
      */

      if (contextHandle != 0) {
          int lockRes = drawable.lockSurface();
          if (NativeSurface.LOCK_SURFACE_NOT_READY == lockRes) {
                // this would be odd ..
                throw new GLException("Surface not ready to lock: "+drawable);
          }
          try {
              destroyImpl();
              contextHandle = 0;
              GLContextShareSet.contextDestroyed(this);
          } finally {
              drawable.unlockSurface();
          }
      }
    } finally {
      lock.unlock();
    }

    resetStates();
  }
  protected abstract void destroyImpl() throws GLException;

  public final void copy(GLContext source, int mask) throws GLException {
    if (source.getHandle() == 0) {
      throw new GLException("Source OpenGL context has not been created");
    }
    if (getHandle() == 0) {
      throw new GLException("Destination OpenGL context has not been created");
    }

    int lockRes = drawable.lockSurface();
    if (NativeSurface.LOCK_SURFACE_NOT_READY == lockRes) {
        // this would be odd ..
        throw new GLException("Surface not ready to lock");
    }
    try {
        copyImpl(source, mask);
    } finally {
      drawable.unlockSurface();
    }
  }
  protected abstract void copyImpl(GLContext source, int mask) throws GLException;

  //----------------------------------------------------------------------
  //

  /**
   * MakeCurrent functionality, which also issues the creation of the actual OpenGL context.<br>
   * The complete callgraph for general OpenGL context creation is:<br>
   * <ul>
   *    <li> {@link #makeCurrent} <i>GLContextImpl</i></li>
   *    <li> {@link #makeCurrentImpl} <i>Platform Implementation</i></li>
   *    <li> {@link #create} <i>Platform Implementation</i></li>
   *    <li> If <code>ARB_create_context</code> is supported:
   *    <ul>
   *        <li> {@link #createContextARB} <i>GLContextImpl</i></li>
   *        <li> {@link #createContextARBImpl} <i>Platform Implementation</i></li>
   *    </ul></li>
   * </ul><br>
   *
   * Once at startup, ie triggered by the singleton constructor of a {@link GLDrawableFactoryImpl} specialization,
   * calling {@link #createContextARB} will query all available OpenGL versions:<br>
   * <ul>
   *    <li> <code>FOR ALL GL* DO</code>:
   *    <ul>
   *        <li> {@link #createContextARBMapVersionsAvailable}
   *        <ul>
   *            <li> {@link #createContextARBVersions}</li>
   *        </ul></li>
   *        <li> {@link #mapVersionAvailable}</li>
   *    </ul></li>
   * </ul><br>
   *
   * @see #makeCurrentImpl
   * @see #create
   * @see #createContextARB
   * @see #createContextARBImpl
   * @see #mapVersionAvailable
   * @see #destroyContextARBImpl
   */
  public int makeCurrent() throws GLException {
    // One context can only be current by one thread,
    // and one thread can only have one context current!
    GLContext current = getCurrent();
    if (current != null) {
      if (current == this) {
        // Assume we don't need to make this context current again
        // For Mac OS X, however, we need to update the context to track resizes
        update();
        return CONTEXT_CURRENT;
      } else {
        current.release();
      }
    }

    if (GLWorkerThread.isStarted() &&
        !GLWorkerThread.isWorkerThread()) {
      // Kick the GLWorkerThread off its current context
      GLWorkerThread.invokeLater(new Runnable() { public void run() {} });
    }

    if (!isCreated()) {
        // verify if the drawable has chosen Capabilities
        if (null == getGLDrawable().getChosenGLCapabilities()) {
            throw new GLException("drawable has no chosen GLCapabilities: "+getGLDrawable());
        }
    }

    lock.lock();
    int res = 0;
    try {
      res = makeCurrentLocking();

      /* FIXME: refactor dependence on Java 2D / JOGL bridge
      if ((tracker != null) &&
          (res == CONTEXT_CURRENT_NEW)) {
        // Increase reference count of GLObjectTracker
        tracker.ref();
      }
      */
    } catch (GLException e) {
      lock.unlock();
      throw(e);
    }
    if (res == CONTEXT_NOT_CURRENT) {
      lock.unlock();
    } else {
      if(res == CONTEXT_CURRENT_NEW) {
        // check if the drawable's and the GL's GLProfile are equal
        // throws an GLException if not 
        getGLDrawable().getGLProfile().verifyEquality(gl.getGLProfile());
      }
      setCurrent(this);

      /* FIXME: refactor dependence on Java 2D / JOGL bridge

      // Try cleaning up any stale server-side OpenGL objects
      // FIXME: not sure what to do here if this throws
      if (deletedObjectTracker != null) {
        deletedObjectTracker.clean(getGL());
      }
      */
    }
    return res;
  }

  // Note: the surface is locked within [makeCurrent .. swap .. release]
  protected final int makeCurrentLocking() throws GLException {
    boolean exceptionOccurred = false;
    int lockRes = drawable.lockSurface();
    try {
      if (NativeSurface.LOCK_SURFACE_NOT_READY == lockRes) {
        return CONTEXT_NOT_CURRENT;
      }
      try {
          if (NativeSurface.LOCK_SURFACE_CHANGED == lockRes) {
            drawable.updateHandle();
          }
          if (0 == drawable.getHandle()) {
              throw new GLException("drawable has invalid handle: "+drawable);
          }
          boolean newCreated = false;
          if (!isCreated()) {
            GLProfile.initProfiles(
                    getGLDrawable().getNativeSurface().getGraphicsConfiguration().getNativeGraphicsConfiguration().getScreen().getDevice());
            newCreated = createImpl(); // may throws exception if fails!
            if (DEBUG) {
                if(newCreated) {
                    System.err.println(getThreadName() + ": !!! Create GL context OK: " + toHexString(contextHandle) + " for " + getClass().getName());
                } else {
                    System.err.println(getThreadName() + ": !!! Create GL context FAILED for " + getClass().getName());
                }
            }
            if(!newCreated) {
                return CONTEXT_NOT_CURRENT;
            }
            GLContextShareSet.contextCreated(this);
          }
          makeCurrentImpl(newCreated);
          return newCreated ? CONTEXT_CURRENT_NEW : CONTEXT_CURRENT ;
      } catch (RuntimeException e) {
        exceptionOccurred = true;
        throw e;
      }
    } finally {
      if (exceptionOccurred) {
        drawable.unlockSurface();
      }
    }
  }
  protected abstract void makeCurrentImpl(boolean newCreatedContext) throws GLException;
  protected abstract boolean createImpl() throws GLException ;

  /** 
   * Platform dependent but harmonized implementation of the <code>ARB_create_context</code>
   * mechanism to create a context.<br>
   *
   * This method is called from {@link #createContextARB}.<br>
   *
   * The implementation shall verify this context with a 
   * <code>MakeContextCurrent</code> call.<br>
   *
   * The implementation shall leave the context current.<br>
   *
   * @param share the shared context or null
   * @param direct flag if direct is requested
   * @param ctxOptionFlags <code>ARB_create_context</code> related, see references below
   * @param major major number
   * @param minor minor number
   * @return the valid context if successfull, or null
   *
   * @see #makeCurrent
   * @see #CTX_PROFILE_COMPAT
   * @see #CTX_OPTION_FORWARD
   * @see #CTX_OPTION_DEBUG
   * @see #makeCurrentImpl
   * @see #create
   * @see #createContextARB
   * @see #createContextARBImpl
   * @see #destroyContextARBImpl
   */
  protected abstract long createContextARBImpl(long share, boolean direct, int ctxOptionFlags, 
                                               int major, int minor);

  /**
   * Destroy the context created by {@link #createContextARBImpl}.
   *
   * @see #makeCurrent
   * @see #makeCurrentImpl
   * @see #create
   * @see #createContextARB
   * @see #createContextARBImpl
   * @see #destroyContextARBImpl
   */
  protected abstract void destroyContextARBImpl(long context);

  /**
   * Platform independent part of using the <code>ARB_create_context</code>
   * mechanism to create a context.<br>
   *
   * The implementation of {@link #create} shall use this protocol in case the platform supports <code>ARB_create_context</code>.<br>
   *
   * This method may call {@link #createContextARBImpl} and {@link #destroyContextARBImpl}. <br>
   *
   * This method will also query all available native OpenGL context when first called,<br>
   * usually the first call should happen with the shared GLContext of the DrawableFactory.<br>
   *
   * The implementation makes the context current, if successful<br>
   *
   * @see #makeCurrentImpl
   * @see #create
   * @see #createContextARB
   * @see #createContextARBImpl
   * @see #destroyContextARBImpl
   */
  protected final long createContextARB(long share, boolean direct,
                                        int major[], int minor[], int ctp[]) 
  {
    AbstractGraphicsConfiguration config = drawable.getNativeSurface().getGraphicsConfiguration().getNativeGraphicsConfiguration();
    AbstractGraphicsDevice device = config.getScreen().getDevice();
    GLCapabilities glCaps = (GLCapabilities) config.getChosenCapabilities();
    GLProfile glp = glCaps.getGLProfile();

    if (DEBUG) {
      System.err.println(getThreadName() + ": !!! createContextARB: mappedVersionsAvailableSet("+device.getConnection()+"): "+
               GLContext.getAvailableGLVersionsSet(device));
    }

    mapGLVersions(device);

    int reqMajor;
    if(glp.isGL4()) {
        reqMajor=4;
    } else if (glp.isGL3()) {
        reqMajor=3;
    } else /* if (glp.isGL2()) */ {
        reqMajor=2;
    }

    boolean compat = glp.isGL2(); // incl GL3bc and GL4bc
    int _major[] = { 0 };
    int _minor[] = { 0 };
    int _ctp[] = { 0 };
    long _ctx = 0;

    if( GLContext.getAvailableGLVersion(device, reqMajor, compat?CTX_PROFILE_COMPAT:CTX_PROFILE_CORE,
                                        _major, _minor, _ctp)) {
        _ctx = createContextARBImpl(share, direct, _ctp[0], _major[0], _minor[0]);
        if(0!=_ctx) {
            setGLFunctionAvailability(true, _major[0], _minor[0], _ctp[0]);
        }
    }
    return _ctx;
  }

  private final void mapGLVersions(AbstractGraphicsDevice device) {
    if ( !GLContext.getAvailableGLVersionsSet(device) ) {
        synchronized (GLContext.deviceVersionAvailable) {
            createContextARBMapVersionsAvailable(4, false /* core   */);  // GL4
            createContextARBMapVersionsAvailable(4, true  /* compat */);  // GL4bc
            createContextARBMapVersionsAvailable(3, false /* core   */);  // GL3
            createContextARBMapVersionsAvailable(3, true  /* compat */);  // GL3bc
            createContextARBMapVersionsAvailable(2, true  /* compat */);  // GL2
            GLContext.setAvailableGLVersionsSet(device);
        }
    } else {
        if(DEBUG) {
            System.err.println(getThreadName() + ": no mapping, all versions set "+device.getConnection());
        }
    }
  }

  private final void createContextARBMapVersionsAvailable(int reqMajor, boolean compat)
  {
    resetStates();

    long _context;
    int reqProfile = compat ? CTX_PROFILE_COMPAT : CTX_PROFILE_CORE ;
    int ctp = CTX_IS_ARB_CREATED | CTX_PROFILE_CORE | CTX_OPTION_ANY; // default
    if(compat) {
        ctp &= ~CTX_PROFILE_CORE ;
        ctp |=  CTX_PROFILE_COMPAT ;
    }

    // FIXME GL3GL4:
    // To avoid OpenGL implementation bugs and raise compatibility
    // within JOGL, we map to the proper GL version.
    // This may change later when GL3 and GL4 drivers become more mature!
    // Bug: To ensure GL profile compatibility within the JOGL application
    // Bug: we always try to map against the highest GL version,
    // Bug: so the use can always cast to a higher one
    // Bug: int majorMax=GLContext.getMaxMajor(); 
    // Bug: int minorMax=GLContext.getMaxMinor(majorMax);
    int majorMax, minorMax;
    int majorMin, minorMin;
    int major[] = new int[1];
    int minor[] = new int[1];
    if( 4 == reqMajor ) {
        majorMax=4; minorMax=GLContext.getMaxMinor(majorMax);
        majorMin=4; minorMin=0;
    } else if( 3 == reqMajor ) {
        majorMax=3; minorMax=GLContext.getMaxMinor(majorMax);
        majorMin=3; minorMin=1;
    } else /* if( glp.isGL2() ) */ {
        majorMax=3; minorMax=0;
        majorMin=1; minorMin=1; // our minimum desktop OpenGL runtime requirements
    }
    _context = createContextARBVersions(0, true, ctp, 
                                        /* max */ majorMax, minorMax,
                                        /* min */ majorMin, minorMin,
                                        /* res */ major, minor);

    if(0==_context && !compat) {
        ctp &= ~CTX_PROFILE_COMPAT ;
        ctp |=  CTX_PROFILE_CORE ;
        ctp &= ~CTX_OPTION_ANY ;
        ctp |=  CTX_OPTION_FORWARD ;
        _context = createContextARBVersions(0, true, ctp, 
                                            /* max */ majorMax, minorMax,
                                            /* min */ majorMin, minorMin,
                                            /* res */ major, minor);
       if(0==_context) {
            // Try a compatible one .. even though not requested .. last resort
            ctp &= ~CTX_PROFILE_CORE ;
            ctp |=  CTX_PROFILE_COMPAT ;
            ctp &= ~CTX_OPTION_FORWARD ;
            ctp |=  CTX_OPTION_ANY ;
            _context = createContextARBVersions(0, true, ctp, 
                                       /* max */ majorMax, minorMax,
                                       /* min */ majorMin, minorMin,
                                       /* res */ major, minor);
       }
    }
    if(0!=_context) {
        AbstractGraphicsDevice device = drawable.getNativeSurface().getGraphicsConfiguration().getNativeGraphicsConfiguration().getScreen().getDevice();
        GLContext.mapAvailableGLVersion(device, reqMajor, reqProfile, major[0], minor[0], ctp);
        setGLFunctionAvailability(true, major[0], minor[0], ctp);
        destroyContextARBImpl(_context);
        resetStates();
        if (DEBUG) {
          System.err.println(getThreadName() + ": !!! createContextARBMapVersionsAvailable HAVE: "+
                  GLContext.getAvailableGLVersionAsString(device, reqMajor, reqProfile));
        }
    } else if (DEBUG) {
        System.err.println(getThreadName() + ": !!! createContextARBMapVersionsAvailable NOPE: "+reqMajor+"."+reqProfile);
    }
  }

  private final long createContextARBVersions(long share, boolean direct, int ctxOptionFlags, 
                                              int majorMax, int minorMax, 
                                              int majorMin, int minorMin, 
                                              int major[], int minor[]) {
    major[0]=majorMax;
    minor[0]=minorMax;
    long _context=0;

    while ( 0==_context &&
            GLContext.isValidGLVersion(major[0], minor[0]) &&
            ( major[0]>majorMin || major[0]==majorMin && minor[0] >=minorMin ) ) {

        if (DEBUG) {
            System.err.println(getThreadName() + ": createContextARBVersions: share "+share+", direct "+direct+", version "+major[0]+"."+minor[0]);
        }
        _context = createContextARBImpl(share, direct, ctxOptionFlags, major[0], minor[0]);

        if(0==_context) {
            if(!GLContext.decrementGLVersion(major, minor)) break;
        }
    }
    return _context;
  }

  //----------------------------------------------------------------------
  // Managing the actual OpenGL version, usually figured at creation time.
  // As a last resort, the GL_VERSION string may be used ..
  //

  /** 
   * If major > 0 || minor > 0 : Use passed values, determined at creation time 
   * If major==0 && minor == 0 : Use GL_VERSION
   * Otherwise .. don't touch ..
   */
  private final void setContextVersion(int major, int minor, int ctp) {
      if (0==ctp) {
        throw new GLException("Invalid GL Version "+major+"."+minor+", ctp "+toHexString(ctp));
      }
      if(major>0 || minor>0) {
          if (!GLContext.isValidGLVersion(major, minor)) {
            GLException e = new GLException("Invalid GL Version "+major+"."+minor+", ctp "+toHexString(ctp));
            throw e;
          }
          ctxMajorVersion = major;
          ctxMinorVersion = minor;
          ctxOptions = ctp;
          ctxVersionString = getGLVersion(ctxMajorVersion, ctxMinorVersion, ctxOptions, getGL().glGetString(GL.GL_VERSION));
          return;
      }

      if(major==0 && minor==0) {
          String versionStr = getGL().glGetString(GL.GL_VERSION);
          if(null==versionStr) {
            throw new GLException("GL_VERSION is NULL: "+this);
          }
          ctxOptions = ctp;

          // Set version
          Version version = new Version(versionStr);
          if (version.isValid()) {
            ctxMajorVersion = version.getMajor();
            ctxMinorVersion = version.getMinor();
            // We cannot promote a non ARB context to >= 3.1,
            // reduce it to 3.0 then.
            if ( ( ctxMajorVersion>3 || ctxMajorVersion==3 && ctxMinorVersion>=1 )
                 && 0 == (ctxOptions & CTX_IS_ARB_CREATED) ) {
                ctxMajorVersion = 3;
                ctxMinorVersion = 0;
            }
            ctxVersionString = getGLVersion(ctxMajorVersion, ctxMinorVersion, ctxOptions, versionStr);
            return;
          }
      }
  }

  //----------------------------------------------------------------------
  // Helpers for various context implementations
  //

  private Object createInstance(GLProfile glp, String suffix, Class[] cstrArgTypes, Object[] cstrArgs) {
    return ReflectionUtil.createInstance(glp.getGLImplBaseClassName()+suffix, cstrArgTypes, cstrArgs, getClass().getClassLoader());
  }

  private boolean verifyInstance(GLProfile glp, String suffix, Object instance) {
    return ReflectionUtil.instanceOf(instance, glp.getGLImplBaseClassName()+suffix);
  }

  /** Create the GL for this context. */
  protected GL createGL(GLProfile glp) {
    GL gl = (GL) createInstance(glp, "Impl", new Class[] { GLProfile.class, GLContextImpl.class }, new Object[] { glp, this } );

    /* FIXME: refactor dependence on Java 2D / JOGL bridge
    if (tracker != null) {
      gl.setObjectTracker(tracker);
    }
    */
    return gl;
  }
  
  public final ProcAddressTable getGLProcAddressTable() {
    return glProcAddressTable;
  }
  
  /**
   * Shall return the platform extension ProcAddressTable,
   * ie for GLXExt, EGLExt, ..
   */
  public abstract ProcAddressTable getPlatformExtProcAddressTable();

  /**
   * Pbuffer support; given that this is a GLContext associated with a
   * pbuffer, binds this pbuffer to its texture target.
   */
  public abstract void bindPbufferToTexture();

  /**
   * Pbuffer support; given that this is a GLContext associated with a
   * pbuffer, releases this pbuffer from its texture target.
   */
  public abstract void releasePbufferFromTexture();

  public abstract ByteBuffer glAllocateMemoryNV(int arg0, float arg1, float arg2, float arg3);

  public final void setSwapInterval(final int interval) {
    GLContext current = getCurrent();
    if (current != this) {
        throw new GLException("This context is not current. Current context: "+current+
                              ", this context "+this);
    }
    setSwapIntervalImpl(interval);
  }
  protected void setSwapIntervalImpl(final int interval) { /** nop per default .. **/  }
  protected int currentSwapInterval = -1; // default: not set yet ..
  public int getSwapInterval() {
    return currentSwapInterval;
  }

  /** Maps the given "platform-independent" function name to a real function
      name. Currently this is only used to map "glAllocateMemoryNV" and
      associated routines to wglAllocateMemoryNV / glXAllocateMemoryNV. */
  protected String mapToRealGLFunctionName(String glFunctionName) {
    Map/*<String, String>*/ map = getFunctionNameMap();
    String lookup = ( null != map ) ? (String) map.get(glFunctionName) : null;
    if (lookup != null) {
      return lookup;
    }
    return glFunctionName;
  }
  protected abstract Map/*<String, String>*/ getFunctionNameMap() ;

  /** Maps the given "platform-independent" extension name to a real
      function name. Currently this is only used to map
      "GL_ARB_pbuffer"      to  "WGL_ARB_pbuffer/GLX_SGIX_pbuffer" and 
      "GL_ARB_pixel_format" to  "WGL_ARB_pixel_format/n.a." 
   */
  protected String mapToRealGLExtensionName(String glExtensionName) {
    Map/*<String, String>*/ map = getExtensionNameMap();
    String lookup = ( null != map ) ? (String) map.get(glExtensionName) : null;
    if (lookup != null) {
      return lookup;
    }
    return glExtensionName;
  }
  protected abstract Map/*<String, String>*/ getExtensionNameMap() ;

  /** Helper routine which resets a ProcAddressTable generated by the
      GLEmitter by looking up anew all of its function pointers. */
  protected void resetProcAddressTable(ProcAddressTable table) {
    table.reset(getDrawableImpl().getGLDynamicLookupHelper() );
  }

  /**
   * Sets the OpenGL implementation class and
   * the cache of which GL functions are available for calling through this
   * context. See {@link #isFunctionAvailable(String)} for more information on
   * the definition of "available".
   * <br>
   * All ProcaddressTables are being determined, the GL version is being set
   * and the extension cache is determined as well.
   *
   * @param force force the setting, even if is already being set.
   *              This might be useful if you change the OpenGL implementation.
   *
   * @see #setContextVersion
   */

  protected final void setGLFunctionAvailability(boolean force, int major, int minor, int ctp) {
    if(null!=this.gl && null!=glProcAddressTable && !force) {
        return; // already done and not forced
    }
    if(null==this.gl || force) {
        setGL(createGL(getGLDrawable().getGLProfile()));
    }

    AbstractGraphicsConfiguration aconfig = drawable.getNativeSurface().getGraphicsConfiguration().getNativeGraphicsConfiguration();
    AbstractGraphicsDevice adevice = aconfig.getScreen().getDevice();
    contextFQN = getContextFQN(adevice, major, minor, ctp);
    if (DEBUG) {
      System.err.println(getThreadName() + ": !!! Context FQN: "+contextFQN);
    }

    updateGLXProcAddressTable(major, minor, ctp);

    //
    // UpdateGLProcAddressTable functionality
    //
    if(null==this.gl) {
        throw new GLException("setGLFunctionAvailability not called yet");
    }

    ProcAddressTable table = null;
    synchronized(mappedContextTypeObjectLock) {
        table = (ProcAddressTable) mappedGLProcAddress.get( contextFQN );
        if(null != table && !verifyInstance(gl.getGLProfile(), "ProcAddressTable", table)) {
            throw new InternalError("GLContext GL ProcAddressTable mapped key("+contextFQN+") -> "+
                  table.getClass().getName()+" not matching "+gl.getGLProfile().getGLImplBaseClassName());
        }
    }
    if(null != table) {
        glProcAddressTable = table;
        if(DEBUG) {
            System.err.println(getThreadName() + ": !!! GLContext GL ProcAddressTable reusing key("+contextFQN+") -> "+table.hashCode());
        }
    } else {
        if (glProcAddressTable == null) {
          glProcAddressTable = (ProcAddressTable) createInstance(gl.getGLProfile(), "ProcAddressTable",
                                                                 new Class[] { FunctionAddressResolver.class } ,
                                                                 new Object[] { new GLProcAddressResolver() } );
        }
        resetProcAddressTable(getGLProcAddressTable());
        synchronized(mappedContextTypeObjectLock) {
            mappedGLProcAddress.put(contextFQN, getGLProcAddressTable());
            if(DEBUG) {
                System.err.println(getThreadName() + ": !!! GLContext GL ProcAddressTable mapping key("+contextFQN+") -> "+getGLProcAddressTable().hashCode());
            }
        }
    }

    //
    // Set GL Version
    //
    setContextVersion(major, minor, ctp);

    //
    // Update ExtensionAvailabilityCache
    //
    ExtensionAvailabilityCache eCache;
    synchronized(mappedContextTypeObjectLock) {
        eCache = (ExtensionAvailabilityCache) mappedExtensionAvailabilityCache.get( contextFQN );
    }
    if(null !=  eCache) {
        extensionAvailability = eCache;
        if(DEBUG) {
            System.err.println(getThreadName() + ": !!! GLContext GL ExtensionAvailabilityCache reusing key("+contextFQN+") -> "+eCache.hashCode());
        }
    } else {
        if(null==extensionAvailability) {
            extensionAvailability = new ExtensionAvailabilityCache(this);
        }
        extensionAvailability.reset();
        synchronized(mappedContextTypeObjectLock) {
            mappedExtensionAvailabilityCache.put(contextFQN, extensionAvailability);
            if(DEBUG) {
                System.err.println(getThreadName() + ": !!! GLContext GL ExtensionAvailabilityCache mapping key("+contextFQN+") -> "+extensionAvailability.hashCode());
            }
        }
    }

    hasNativeES2Methods = isGLES2() || isExtensionAvailable("GL_ARB_ES2_compatibility") ;
  }

  /**
   * Updates the platform's 'GLX' function cache
   */
  protected abstract void updateGLXProcAddressTable(int major, int minor, int ctp);

  protected boolean hasNativeES2Methods = false;

  public final boolean hasNativeES2Methods() { return hasNativeES2Methods; }

  /**
   * Returns true if the specified OpenGL core- or extension-function can be
   * successfully called using this GL context given the current host (OpenGL
   * <i>client</i>) and display (OpenGL <i>server</i>) configuration.
   *
   * See {@link GL#isFunctionAvailable(String)} for more details.
   *
   * @param glFunctionName the name of the OpenGL function (e.g., use
   * "glPolygonOffsetEXT" or "glPolygonOffset" to check if the {@link
   * javax.media.opengl.GL#glPolygonOffset(float,float)} is available).
   */
  public boolean isFunctionAvailable(String glFunctionName) {
    // Check GL 1st (cached)
    ProcAddressTable pTable = getGLProcAddressTable(); // null if ctx not created once
    if(null!=pTable) {
        try {
            if(0!=pTable.getAddressFor(glFunctionName)) {
                return true;
            }
        } catch (Exception e) {}
    }

    // Check platform extensions 2nd (cached) - had to be enabled once
    pTable = getPlatformExtProcAddressTable(); // null if ctx not created once
    if(null!=pTable) {
        try {
            if(0!=pTable.getAddressFor(glFunctionName)) {
                return true;
            }
        } catch (Exception e) {}
    }

    // dynamic function lookup at last incl name aliasing (not cached)
    DynamicLookupHelper dynLookup = getDrawableImpl().getGLDynamicLookupHelper();
    String tmpBase = GLExtensionNames.normalizeVEN(GLExtensionNames.normalizeARB(glFunctionName, true), true);
    long addr = 0;
    int  variants = GLExtensionNames.getFuncNamePermutationNumber(tmpBase);
    for(int i = 0; 0==addr && i < variants; i++) {
        String tmp = GLExtensionNames.getFuncNamePermutation(tmpBase, i);
        try {
            addr = dynLookup.dynamicLookupFunction(tmp);
        } catch (Exception e) { }
    }
    if(0!=addr) {
        return true;
    }
    return false;
  }

  /**
   * Returns true if the specified OpenGL extension can be
   * successfully called using this GL context given the current host (OpenGL
   * <i>client</i>) and display (OpenGL <i>server</i>) configuration.
   *
   * See {@link GL#isExtensionAvailable(String)} for more details.
   *
   * @param glExtensionName the name of the OpenGL extension (e.g.,
   * "GL_VERTEX_PROGRAM_ARB").
   */
  public boolean isExtensionAvailable(String glExtensionName) {
      if(null!=extensionAvailability) {
        return extensionAvailability.isExtensionAvailable(mapToRealGLExtensionName(glExtensionName));
      }
      return false;
  }

  public String getPlatformExtensionsString() {
      if(null!=extensionAvailability) {
        return extensionAvailability.getPlatformExtensionsString();
      }
      return null;
  }

  public String getGLExtensions() {
      if(null!=extensionAvailability) {
        return extensionAvailability.getGLExtensions();
      }
      return null;
  }

  public boolean isExtensionCacheInitialized() {
      if(null!=extensionAvailability) {
        return extensionAvailability.isInitialized();
      }
      return false;
  }

  /** Indicates which floating-point pbuffer implementation is in
      use. Returns one of GLPbuffer.APPLE_FLOAT, GLPbuffer.ATI_FLOAT,
      or GLPbuffer.NV_FLOAT. */
  public int getFloatingPointMode() throws GLException {
    throw new GLException("Not supported on non-pbuffer contexts");
  }

  /** On some platforms the mismatch between OpenGL's coordinate
      system (origin at bottom left) and the window system's
      coordinate system (origin at top left) necessitates a vertical
      flip of pixels read from offscreen contexts. */
  public abstract boolean offscreenImageNeedsVerticalFlip();

  /** Only called for offscreen contexts; needed by glReadPixels */
  public abstract int getOffscreenContextPixelDataType();

  //----------------------------------------------------------------------
  // Helpers for buffer object optimizations
  
  public void setBufferSizeTracker(GLBufferSizeTracker bufferSizeTracker) {
    this.bufferSizeTracker = bufferSizeTracker;
  }

  public GLBufferSizeTracker getBufferSizeTracker() {
    return bufferSizeTracker;
  }

  public GLBufferStateTracker getBufferStateTracker() {
    return bufferStateTracker;
  }

  public GLStateTracker getGLStateTracker() {
    return glStateTracker;
  }

  //---------------------------------------------------------------------------
  // Helpers for context optimization where the last context is left
  // current on the OpenGL worker thread
  //

  public boolean hasWaiters() {
    return lock.hasWaiters();
  }

  /* FIXME: needed only by the Java 2D / JOGL bridge; refactor

  public GLContextImpl(GLContext shareWith) {
    this(shareWith, false);
  }
  
  public GLContextImpl(GLContext shareWith, boolean dontShareWithJava2D) {
    extensionAvailability = new ExtensionAvailabilityCache(this);
    GLContext shareContext = shareWith;
    if (!dontShareWithJava2D) {
      shareContext = Java2D.filterShareContext(shareWith);
    }
    if (shareContext != null) {
      GLContextShareSet.registerSharing(this, shareContext);
    }
    // Always indicate real behind-the-scenes sharing to track deleted objects
    if (shareContext == null) {
      shareContext = Java2D.filterShareContext(shareWith);
    }
    GLContextShareSet.registerForObjectTracking(shareWith, this, shareContext);
    GLContextShareSet.registerForBufferObjectSharing(shareWith, this);
    // This must occur after the above calls into the
    // GLContextShareSet, which set up state needed by the GL object
    setGL(createGL());
  }

  //---------------------------------------------------------------------------
  // Helpers for integration with Java2D/OpenGL pipeline when FBOs are
  // being used
  //

  public void setObjectTracker(GLObjectTracker tracker) {
    this.tracker = tracker;
  }
  
  public GLObjectTracker getObjectTracker() {
    return tracker;
  }

  public void setDeletedObjectTracker(GLObjectTracker deletedObjectTracker) {
    this.deletedObjectTracker = deletedObjectTracker;
  }

  public GLObjectTracker getDeletedObjectTracker() {
    return deletedObjectTracker;
  }

  // Tracks creation and deletion of server-side OpenGL objects when
  // the Java2D/OpenGL pipeline is active and using FBOs to render
  private GLObjectTracker tracker;
  // Supports deletion of these objects when no other context is
  // current which can support immediate deletion of them
  private GLObjectTracker deletedObjectTracker;

  */

  /**
   * A class for storing and comparing OpenGL version numbers.
   * This only works for desktop OpenGL at the moment.
   */
  private static class Version implements Comparable
  {
    private boolean valid;
    private int major, minor, sub;
    public Version(int majorRev, int minorRev, int subMinorRev)
    {
      major = majorRev;
      minor = minorRev;
      sub = subMinorRev;
    }

    /**
     * @param versionString must be of the form "GL_VERSION_X" or
     * "GL_VERSION_X_Y" or "GL_VERSION_X_Y_Z" or "X.Y", where X, Y,
     * and Z are integers.
     *
     * @exception IllegalArgumentException if the argument is not a valid
     * OpenGL version identifier
     */
    public Version(String versionString)
    {
      try 
      {
        if (versionString.startsWith("GL_VERSION_"))
        {
          StringTokenizer tok = new StringTokenizer(versionString, "_");

          tok.nextToken(); // GL_
          tok.nextToken(); // VERSION_ 
          if (!tok.hasMoreTokens()) { major = 0; return; }
          major = Integer.valueOf(tok.nextToken()).intValue();
          if (!tok.hasMoreTokens()) { minor = 0; return; }
          minor = Integer.valueOf(tok.nextToken()).intValue();
          if (!tok.hasMoreTokens()) { sub = 0; return; }
          sub = Integer.valueOf(tok.nextToken()).intValue();
        }
        else
        {
          int radix = 10;
          if (versionString.length() > 2) {
            if (Character.isDigit(versionString.charAt(0)) &&
                versionString.charAt(1) == '.' &&
                Character.isDigit(versionString.charAt(2))) {
              major = Character.digit(versionString.charAt(0), radix);
              minor = Character.digit(versionString.charAt(2), radix);

              // See if there's version-specific information which might
              // imply a more recent OpenGL version
              StringTokenizer tok = new StringTokenizer(versionString, " ");
              if (tok.hasMoreTokens()) {
                tok.nextToken();
                if (tok.hasMoreTokens()) {
                  String token = tok.nextToken();
                  int i = 0;
                  while (i < token.length() && !Character.isDigit(token.charAt(i))) {
                    i++;
                  }
                  if (i < token.length() - 2 &&
                      Character.isDigit(token.charAt(i)) &&
                      token.charAt(i+1) == '.' &&
                      Character.isDigit(token.charAt(i+2))) {
                    int altMajor = Character.digit(token.charAt(i), radix);
                    int altMinor = Character.digit(token.charAt(i+2), radix);
                    // Avoid possibly confusing situations by putting some
                    // constraints on the upgrades we do to the major and
                    // minor versions
                    if ((altMajor == major && altMinor > minor) ||
                        altMajor == major + 1) {
                      major = altMajor;
                      minor = altMinor;
                    }
                  }
                }
              }
            }
          }
        }
        valid = true;
      }
      catch (Exception e)
      {
        e.printStackTrace();
        // FIXME: refactor desktop OpenGL dependencies and make this
        // class work properly for OpenGL ES
        System.err.println("Info: ExtensionAvailabilityCache: FunctionAvailabilityCache.Version.<init>: "+e);
        major = 1;
        minor = 0;
        /*
        throw (IllegalArgumentException)
          new IllegalArgumentException(
            "Illegally formatted version identifier: \"" + versionString + "\"")
              .initCause(e);
        */
      }
    }

    public boolean isValid() {
      return valid;
    }

    public int compareTo(Object o)
    {
      Version vo = (Version)o;
      if (major > vo.major) return 1; 
      else if (major < vo.major) return -1; 
      else if (minor > vo.minor) return 1; 
      else if (minor < vo.minor) return -1; 
      else if (sub > vo.sub) return 1; 
      else if (sub < vo.sub) return -1; 

      return 0; // they are equal
    }

    public int getMajor() {
      return major;
    }

    public int getMinor() {
      return minor;
    }
    
  } // end class Version

}
