def branch = 'master'
def work_dir = './tf'
def ansible_dir = './ansible'
def git_repo = 'https://github.com/git67/terraform.git'
def credential_id = 'hs-jenkins-terraform'
def build_name = 'build'
def build_no = '1'
def aws_profile = 'devops'

def echoBanner(def ... msgs) {
   echo createBanner(msgs)
}
def errorBanner(def ... msgs) {
   error(createBanner(msgs))
}
def createBanner(def ... msgs) {
   return """
       ===========================================
       ${msgFlatten(null, msgs).join("\n        ")}
       ===========================================
   """
}
def msgFlatten(def list, def msgs) {
   list = list ?: []
   if (!(msgs instanceof String) && !(msgs instanceof GString)) {
       msgs.each { msg ->
           list = msgFlatten(list, msg)
       }
   }
   else {
       list += msgs
   }

   return  list
}

pipeline {
    agent {
        label 'dev-build'
        }
    stages {
        stage('pre stage - clear env') {
            steps {
                timeout(time: 1, unit: 'MINUTES') {
                    echoBanner("pre clear env")
                    sh """
                    [ -d ${work_dir} ] && cd ${work_dir}
                    rm -rf .terraform* log state
                    """
                }
            }
        }
        stage('test stage - check aws connect') {
            steps {
                timeout(time: 2, unit: 'MINUTES') {
                    echoBanner("check aws connect")
                    sh """
                    aws configure list --profile ${aws_profile}
                    aws ec2 describe-vpcs --profile ${aws_profile}
                    """
                }
            }
        }
        stage('test stage - git checkout source code') {
            steps {
                timeout(time: 1, unit: 'MINUTES') {
                    echoBanner("git checkout")
                    git branch: branch, 
                        credentialsId: credential_id,
                        url: git_repo
                }
            }
        }
        stage('deploy stage - initializing terraform') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    echoBanner("initializing terraform")
                    sh """
                    cd ${work_dir}
                    terraform init
                    """
                }
            }    
        }
        stage('deploy stage - run terraform plan') {
            steps {
                timeout(time: 2, unit: 'MINUTES') {
                    echoBanner("run terraform plan")
                    sh """
                    cd ${work_dir}
                    . ./.env
                    terraform plan
                    """
                }
            }    
        } 
        stage('deploy stage - run terraform validate') {
            steps {
                timeout(time: 2, unit: 'MINUTES') {
                    echoBanner("run terraform validate")
                    sh """
                    cd ${work_dir}
                    . ./.env
                    terraform validate
                    """
                }
            }    
        } 
        stage('deploy stage - run terraform apply') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    echoBanner("run terraform apply")
                    sh """
                    cd ${work_dir}
                    . ./.env
                    terraform apply -auto-approve
                    """
                }
            }    
        } 
        stage('deploy stage - run ansible syntax-check') {
            steps {
                timeout(time: 2, unit: 'MINUTES') {
                    echoBanner("run ansible syntax-check")
                    sh """
                    pwd
                    cd ${ansible_dir}
                    . ./.env
                    ansible-playbook p_base_configure_ansible.yml --syntax-check
                    ansible-playbook p_configure_apache.yml --syntax-check
                    """
                }
            }
        }    
        stage('deploy stage - run ansible') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    echoBanner("run ansible")
                    sh """
                    cd ${ansible_dir}
                    . ./.env
                    _create_ansible_user
                    _configure_apache
                    """
                }
            }    
        } 
        stage('test stage - probe built environment') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    echoBanner("run probe aws env")
                    sh """
                    sleep 60
                    cd ${work_dir}
                    . ./.env
                    ELB=`terraform state list | grep elb`
                    ELB_DNS=`terraform state show \$ELB | grep dns_name | cut -d '"' -f2|cut -d '"' -f1`
                    nslookup www.google.com
                    nslookup \$ELB_DNS
                    ./files/scripts/probe_elb.sh \$ELB_DNS 8080 1
                    """
                }
            }    
        } 
        stage('post stage - cleanup environment') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    echoBanner("cleanup")
                    sh """
                    cd ${work_dir}
                    . ./.env
		            terraform destroy -auto-approve
                    """
                }
            }    
        } 
    } 
    post {  
         always {  
            echoBanner("always dummy") 
            sh """
            cd ${work_dir}
            """
         }  
         success {  
            echoBanner("send success mail") 
            mail to: 'git67@gmx.de',
            charset: 'UTF-8', 
            subject: "Overall: ${currentBuild.fullDisplayName} Job: ${env.JOB_NAME}",
            body: "Project: ${env.JOB_NAME} \nBuild Number: ${env.BUILD_NUMBER} \nURI build: ${env.BUILD_URL} \nState: ${currentBuild.result}"
         }  
         failure {
            echoBanner("error cleanup")
            sh """
            cd ${work_dir}
            . ./.env
		    terraform destroy -auto-approve
            """
           
            errorBanner("send error mail") 
            mail to: 'git67@gmx.de',
            charset: 'UTF-8', 
            subject: "Failure: ${currentBuild.fullDisplayName} Job: ${env.JOB_NAME}",
            body: "Project: ${env.JOB_NAME} \nBuild Number: ${env.BUILD_NUMBER} \nURI build: ${env.BUILD_URL} \nState: ${currentBuild.result}"
         }  
         unstable {  
            echoBanner("unstable placholder")  
         }
         changed {  
            mail to: 'git67@gmx.de',
            charset: 'UTF-8', 
            subject: "Changed: ${currentBuild.fullDisplayName} Job: ${env.JOB_NAME}",
            body: "Project: ${env.JOB_NAME} \nBuild Number: ${env.BUILD_NUMBER} \nURI build: ${env.BUILD_URL} \nState: ${currentBuild.result}"
         }  
    }
}


