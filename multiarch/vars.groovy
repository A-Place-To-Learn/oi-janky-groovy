// TODO refactor "Architectures"-supporting pipelines to use the manifest file itself as the source-of-truth for supported architectures!

// https://github.com/jenkinsci/pipeline-examples/blob/666e5e3f8104efd090e698aa9b5bc09dd6bf5997/docs/BEST_PRACTICES.md#groovy-gotchas
// tl;dr, iterating over Maps in pipeline groovy is pretty broken in real-world use
archesMeta = [
	//['amd64', [:]],
	['arm32v5', [:]],
	['arm32v6', [:]],
	['arm32v7', [:]],
	['arm64v8', [:]],
	['i386', [:]],
	['ppc64le', [:]],
	['s390x', [:]],
	['windows-amd64', [:]],
]
dpkgArches = [
	'amd64': 'amd64',
	'arm32v5': 'armel',
	'arm32v6': 'armhf', // Raspberry Pi, making life hard...
	'arm32v7': 'armhf',
	'arm64v8': 'arm64',
	'i386': 'i386',
	'ppc64le': 'ppc64el',
	's390x': 's390x',
]

def defaultImageMeta = [
	['arches', ['amd64'] as Set],
	['pipeline', 'multiarch/target-outdated-pipeline.groovy'],
]

imagesMeta = [:]

// base images (requires custom pipelines for now)
imagesMeta['alpine'] = [
	'arches': [
		// see http://dl-cdn.alpinelinux.org/alpine/edge/main/
		'amd64',
		'arm32v6',
		'arm64v8',
		'i386',
		'ppc64le',
		's390x',
	] as Set,
	'map': [
		// https://wiki.alpinelinux.org/wiki/Alpine_on_ARM
		// "Currently Alpine supports armv6/armhf arch"
		'amd64': 'x86_64',
		'arm32v6': 'armhf',
		'arm32v7': 'armhf', // Raspberry Pi, making life hard...
		'arm64v8': 'aarch64',
		'i386': 'x86',
		'ppc64le': 'ppc64le',
		's390x': 's390x',
	],
	'pipeline': 'multiarch/target-alpine-pipeline.groovy',
	'cron': '@weekly',
]
imagesMeta['fedora'] = [
	'arches': [
		// see https://dl.fedoraproject.org/pub/fedora/ and https://dl.fedoraproject.org/pub/fedora-secondary/
		// $ curl -fsSL 'https://dl.fedoraproject.org/pub/fedora/imagelist-fedora' 'https://dl.fedoraproject.org/pub/fedora-secondary/imagelist-fedora-secondary' | grep -E '^([.]/)?(linux/)?releases/[^/]+/Docker/' | sed -r -e 's!^([.]/)?(linux/)?releases/!!' | cut -d/ -f3 | sort -u
		'amd64',
		'arm32v7',
		'arm64v8',
		'ppc64le',
	] as Set,
	'map': [
		// our-arch-name: fedora-arch-name
		'amd64': 'x86_64',
		'arm32v7': 'armhfp',
		'arm64v8': 'aarch64',
		'ppc64le': 'ppc64le',
		// https://fedoraproject.org/wiki/Raspberry_Pi#What_about_support_for_the_Raspberry_Pi_Models_A.2FA.2B.2C_B.2FB.2B_.28generation_1.29.2C_Zero.2FZeroW_and_Compute_Module.3F
		// "Fedora doesn't, and NEVER will, support ARMv6 processors."
	],
	'pipeline': 'multiarch/target-fedora-pipeline.groovy',
	'cron': '@weekly',
]
imagesMeta['opensuse'] = [
	'arches': [
		// see http://download.opensuse.org/repositories/Virtualization:/containers:/images:/openSUSE-Tumbleweed/images/
		'amd64',
		'arm32v7',
		'arm64v8',
		'ppc64le',
		's390x',
	] as Set,
	'map': [
		// our-arch-name: opensuse-arch-name
		'amd64': 'x86_64',
		'arm32v7': 'armv7l',
		'arm64v8': 'aarch64',
		'ppc64le': 'ppc64le',
		's390x': 's390x',
	],
	'pipeline': 'multiarch/target-opensuse-pipeline.groovy',
	'cron': '@weekly',
]

// other images (whose "supported arches" lists are normally going to be a combination of their upstream image arches)
imagesMeta['bash'] = [
	'arches': imagesMeta['alpine']['arches'],
]

// list of arches
arches = []
// list of images
images = []

// given an arch, returns a list of images
def archImages(arch) {
	ret = []
	for (image in images) {
		if (arch in imagesMeta[image]['arches']) {
			ret << image
		}
	}
	return ret as Set
}

// given an arch, returns a target namespace
def archNamespace(arch) {
	return arch.replaceAll(/^windows-/, 'win')
}

// given an arch/image combo, returns a target node expression
def node(arch, image) {
	return 'multiarch-' + arch
}

def docsNode(arch, image) {
	return ''
}

for (int i = 0; i < archesMeta.size(); ++i) {
	def arch = archesMeta[i][0]

	arches << arch
}
arches = arches as Set
for (image in imagesMeta.keySet()) {
	def imageMeta = imagesMeta[image]

	// apply "defaultImageMeta" for missing bits
	//   wouldn't it be grand if we could just use "map1 + map2" here??
	//   dat Jenkins sandbox...
	for (int j = 0; j < defaultImageMeta.size(); ++j) {
		def key = defaultImageMeta[j][0]
		def val = defaultImageMeta[j][1]
		if (imageMeta[key] == null) {
			imageMeta[key] = val
		}
	}

	images << image
	imagesMeta[image] = imageMeta
}
images = images as Set

def _invokeWithContext(def context, Closure cl) {
	cl.resolveStrategy = Closure.DELEGATE_FIRST
	cl.delegate = context
	cl()
}

def prebuildSetup(context) {
	_invokeWithContext(context) {
		env.ACT_ON_IMAGE = env.JOB_BASE_NAME // "memcached", etc
		env.ACT_ON_ARCH = env.JOB_NAME.split('/')[-2] // "i386", etc

		env.TARGET_NAMESPACE = archNamespace(env.ACT_ON_ARCH)

		// we'll pull images explicitly -- we don't want it to ever happen _implicitly_ (since the architecture will be wrong if it does)
		env.BASHBREW_PULL = 'never'
	}
}

def seedCache(context) {
	_invokeWithContext(context) {
		dir(env.BASHBREW_CACHE) {
			// clear this directory first (since we "stash" it later, and want it to be as small as it can be for that)
			deleteDir()
		}

		stage('Seed Cache') {
			sh '''
				# ensure the bashbrew cache directory exists, and has an initialized Git repo
				bashbrew from --apply-constraints https://raw.githubusercontent.com/docker-library/official-images/master/library/hello-world > /dev/null

				# and fill it with our newly generated commit (so that "bashbrew build" can DTRT)
				git -C "$BASHBREW_CACHE/git" fetch "$PWD" HEAD:
			'''
		}
	}
}

def generateStackbrewLibrary(context) {
	context.stage('Generate') {
		sh '''
			mkdir -p "$BASHBREW_LIBRARY"
			./generate-stackbrew-library.sh > "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			cat "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			bashbrew cat "$ACT_ON_IMAGE"
			bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
		'''
	}
}

def scrubBashbrewGitRepo(context) {
	context.stage('Scrub GitRepo') {
		sh '''#!/usr/bin/env bash
			set -Eeuo pipefail
			{
				echo 'GitRepo: https://doi-janky.infosiftr.net'
				bashbrew cat "$ACT_ON_IMAGE" | grep -vE '^Git(Repo|Fetch):'
			} > bashbrew-tmp
			mv -v bashbrew-tmp "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			cat "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			bashbrew cat "$ACT_ON_IMAGE"
			bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
		'''
	}
}

def pullFakeFroms(context) {
	context.withEnv([
		'BASHBREW_FROMS_TEMPLATE=' + '''
			{{- range $.Entries -}}
				{{- if not ($.SkipConstraints .) -}}
					{{- $.DockerFrom . -}}
					{{- "\\n" -}}
				{{- end -}}
			{{- end -}}
		''',
	]) {
		stage('Pull') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail
				set -x

				# gather a list of expected parents
				parents="$(
					bashbrew cat -f "$BASHBREW_FROMS_TEMPLATE" "$ACT_ON_IMAGE" 2>/dev/null \\
						| sort -u \\
						| grep -vE '^$|^scratch$|^'"$ACT_ON_IMAGE"'(:|$)' \\
						|| true
				)"
				# all parents might be "scratch", in which case "$parents" will be empty

				if [ -n "$parents" ]; then
					# pull the ones appropriate for our target architecture
					echo "$parents" \\
						| awk -v ns="$TARGET_NAMESPACE" '{ if (/\\//) { print $0 } else { print ns "/" $0 } }' \\
						| xargs -rtn1 docker pull \\
						|| true

					# ... and then tag them without the namespace (so "bashbrew build" can "just work" as-is)
					echo "$parents" \\
						| awk -v ns="$TARGET_NAMESPACE" '!/\\// { print ns "/" $0; print }' \\
						| xargs -rtn2 docker tag \\
						|| true
				fi
			'''

			// gather a list of tags for which we successfully fetched their FROM
			env.TAGS = sh(returnStdout: true, script: '''#!/usr/bin/env bash
				set -Eeuo pipefail

				# gather a list of tags we've seen (filled in build order) so we can catch "FROM $ACT_ON_IMAGE:xxx"
				declare -A seen=()

				for tag in $(bashbrew list --apply-constraints --build-order --uniq "$ACT_ON_IMAGE" 2>/dev/null); do
					for from in $(bashbrew cat -f "$BASHBREW_FROMS_TEMPLATE" "$tag" 2>/dev/null); do
						if [ "$from" = 'scratch' ]; then
							# scratch doesn't exist, but is permissible
							continue
						fi

						if [ -n "${seen[$from]:-}" ]; then
							# this image is FROM one we're already planning to build, it's good
							continue
						fi

						if ! docker inspect --type image "$from" > /dev/null 2>&1; then
							# skip anything we couldn't successfully pull/tag above
							continue 2
						fi
					done

					echo "$tag"

					# add all aliases to "seen" so we can accurately collect things "FROM $ACT_ON_IMAGE:xxx"
					for otherTag in $(bashbrew list "$tag"); do
						seen[$otherTag]=1
					done
				done
			''').trim()

			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail

				if [ -z "${TAGS:-}" ]; then
					echo >&2 'Error: none of the parents for the tags of this image could be fetched! (so none of them can be built)'
					exit 1
				fi
			'''
		}
	}
}

def createFakeBashbrew(context) {
	pullFakeFroms(context)

	context.stage('Fake It!') {
		sh '''#!/usr/bin/env bash
			{
				echo "Maintainers: Docker Library Bot <$ACT_ON_ARCH> (@docker-library-bot)"
				echo
				bashbrew cat $TAGS
			} > bashbrew-tmp
			set -x
			mv -v bashbrew-tmp "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			cat "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			bashbrew cat "$ACT_ON_IMAGE"
			bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
		'''
	}
}

def bashbrewBuildAndPush(context) {
	_invokeWithContext(context) {
		stage('Build') {
			retry(3) {
				sh '''
					bashbrew build "$ACT_ON_IMAGE"
				'''
			}
		}

		stage('Tag') {
			sh '''
				bashbrew tag --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
			'''
		}

		stage('Push') {
			retry(3) {
				sh '''
					bashbrew push --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
				'''
			}
		}
	}
}

def stashBashbrewBits(context) {
	_invokeWithContext(context) {
		if (env.BASHBREW_CACHE) {
			dir(env.BASHBREW_CACHE) {
				stash name: 'bashbrew-cache'
			}
		}
		if (env.BASHBREW_LIBRARY) {
			dir(env.BASHBREW_LIBRARY) {
				stash includes: env.ACT_ON_IMAGE, name: 'bashbrew-library'
			}
		}
	}
}

def unstashBashbrewBits(context) {
	_invokeWithContext(context) {
		if (env.BASHBREW_CACHE) {
			dir(env.BASHBREW_CACHE) {
				deleteDir()
				unstash 'bashbrew-cache'
			}
		}
		if (env.BASHBREW_LIBRARY) {
			dir(env.BASHBREW_LIBRARY) {
				deleteDir()
				unstash 'bashbrew-library'
			}
		}
	}
}

def docsBuildAndPush(context) {
	context.node(docsNode(context.env.ACT_ON_ARCH, context.env.ACT_ON_IMAGE)) {
		env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
		if (env.BASHBREW_CACHE) {
			env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
		}
		unstashBashbrewBits(this)

		stage('Checkout Docs') {
			checkout(
				poll: true,
				changelog: false,
				scm: [
					$class: 'GitSCM',
					userRemoteConfigs: [[
						url: 'https://github.com/docker-library/docs.git',
					]],
					branches: [[name: '*/master']],
					extensions: [
						[
							$class: 'CleanCheckout',
						],
						[
							$class: 'RelativeTargetDirectory',
							relativeTargetDir: 'd',
						],
						[
							$class: 'PathRestriction',
							excludedRegions: '',
							includedRegions: [
								env.ACT_ON_IMAGE + '/**',
								'*.pl',
								'*.sh',
								'.*/**',
							].join('\n'),
						],
					],
					doGenerateSubmoduleConfigurations: false,
					submoduleCfg: [],
				],
			)
		}

		ansiColor('xterm') { dir('d') {
			stage('Update Docs') {
				sh '''
					# add a link to Jenkins build status
					cat >> .template-helpers/generate-dockerfile-links-partial.sh <<-EOSH
						cat <<-'EOBADGE'
							[![Build Status](${JOB_URL%/}/badge/icon) (\\`$TARGET_NAMESPACE/$ACT_ON_IMAGE\\` build job)](${JOB_URL})
						EOBADGE
					EOSH

					./update.sh "$TARGET_NAMESPACE/$ACT_ON_IMAGE"
				'''
			}

			stage('Diff Docs') {
				sh '''
					git diff --color
				'''
			}

			withCredentials([[
				$class: 'UsernamePasswordMultiBinding',
				credentialsId: 'docker-hub-' + env.ACT_ON_ARCH.replaceAll(/^[^-]+-/, ''),
				usernameVariable: 'USERNAME',
				passwordVariable: 'PASSWORD',
			]]) {
				stage('Push Docs') {
					sh '''
						dockerImage="docker-library-docs:$ACT_ON_ARCH-$ACT_ON_IMAGE"
						docker build --pull -t "$dockerImage" -q .
						test -t 1 && it='-it' || it='-i'
						set +x
						docker run "$it" --rm -e TERM \
							--entrypoint './push.pl' \
							"$dockerImage" \
							--username "$USERNAME" \
							--password "$PASSWORD" \
							--batchmode \
							"$TARGET_NAMESPACE/$ACT_ON_IMAGE"
					'''
				}
			}
		} }
	}
}

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
